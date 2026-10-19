package fr.tapissync.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import fr.tapissync.AppLog
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Fait passer l'appareil Android pour un capteur de foulée Bluetooth (profil RSC),
 * afin que la montre reçoive la vitesse et la distance du tapis.
 * Tout tourne sur le fil principal ; les callbacks Bluetooth y sont renvoyés.
 */
@SuppressLint("MissingPermission")
class FootpodBroadcaster(context: Context) : TreadmillManager.Listener {

    enum class State(val label: String) {
        OFF("Désactivé"),
        STARTING("Démarrage…"),
        ADVERTISING("En attente de la montre"),
        CONNECTED("Montre connectée"),
        ERROR("Erreur"),
    }

    fun interface Listener {
        fun onFootpodChanged()
    }

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = manager?.adapter

    val listeners = CopyOnWriteArraySet<Listener>()

    var state = State.OFF
        private set
    var message: String? = null
        private set

    /** Cadence envoyée (pas/min). 0 = pas de cadence. */
    var cadence = 0

    /** Vitesse actuellement diffusée (km/h). */
    var speedKmh = 0.0
        private set

    /** Distance cumulée envoyée à la montre depuis l'activation (m). */
    var totalDistanceM = 0.0
        private set

    private var server: BluetoothGattServer? = null
    private var measurement: BluetoothGattCharacteristic? = null
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private val devices = LinkedHashMap<String, BluetoothDevice>()
    private val subscribed = HashSet<String>()
    private var advertising = false
    private var lastTickMs = 0L
    private var lastDataMs = 0L

    val connectedNames: List<String>
        get() = devices.values.map { d -> safeName(d) ?: d.address }

    // ---------- Permissions et compatibilité ----------

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** Vrai si la puce Bluetooth sait émettre comme un capteur (mode périphérique). */
    fun isSupported(): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled) return true // on ne peut pas savoir tant que le Bluetooth est coupé
        return a.bluetoothLeAdvertiser != null && a.isMultipleAdvertisementSupported
    }

    // ---------- Démarrage / arrêt ----------

    fun start() {
        if (state != State.OFF && state != State.ERROR) return
        val a = adapter
        when {
            a == null -> return fail("Bluetooth absent sur cet appareil.")
            !a.isEnabled -> return fail("Active le Bluetooth.")
            !hasPermissions() -> return fail("Autorisation Bluetooth manquante.")
            a.bluetoothLeAdvertiser == null || !a.isMultipleAdvertisementSupported ->
                return fail("Cet appareil ne sait pas émettre comme un capteur Bluetooth.")
        }
        setState(State.STARTING, null)
        val s = try {
            manager?.openGattServer(ctx, serverCallback)
        } catch (e: Exception) {
            null
        } ?: return fail("Impossible d'ouvrir le serveur Bluetooth.")
        server = s
        pendingServices.clear()
        pendingServices.addLast(buildRscService())
        pendingServices.addLast(buildBatteryService())
        pendingServices.addLast(buildDeviceInfoService())
        addNextService()
    }

    fun stop() {
        main.removeCallbacks(tick)
        stopAdvertising()
        val s = server
        server = null
        measurement = null
        try {
            s?.clearServices()
            s?.close()
        } catch (_: Exception) {
        }
        devices.clear()
        subscribed.clear()
        pendingServices.clear()
        setState(State.OFF, null)
        AppLog.add("Foulée", "Capteur arrêté")
    }

    private fun fail(msg: String) {
        AppLog.add("Foulée", msg)
        main.removeCallbacks(tick)
        stopAdvertising()
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        setState(State.ERROR, msg)
    }

    private fun setState(s: State, msg: String?) {
        state = s
        message = msg
        notifyListeners()
    }

    private fun notifyListeners() = listeners.forEach { it.onFootpodChanged() }

    // ---------- Services GATT ----------

    private fun readable(uuid: java.util.UUID) = BluetoothGattCharacteristic(
        uuid, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ,
    )

    private fun cccd() = BluetoothGattDescriptor(
        Rsc.CCCD,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
    )

    private fun buildRscService(): BluetoothGattService {
        val svc = BluetoothGattService(Rsc.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val m = BluetoothGattCharacteristic(
            Rsc.MEASUREMENT, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0,
        )
        m.addDescriptor(cccd())
        measurement = m
        svc.addCharacteristic(m)
        svc.addCharacteristic(readable(Rsc.FEATURE))
        svc.addCharacteristic(readable(Rsc.SENSOR_LOCATION))
        return svc
    }

    /** Service batterie : certaines montres Garmin l'attendent pour appairer un capteur. */
    private fun buildBatteryService(): BluetoothGattService {
        val svc = BluetoothGattService(Rsc.BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val level = BluetoothGattCharacteristic(
            Rsc.BATTERY_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        level.addDescriptor(cccd())
        svc.addCharacteristic(level)
        return svc
    }

    private fun buildDeviceInfoService(): BluetoothGattService {
        val svc = BluetoothGattService(Rsc.DEVICE_INFO_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(readable(Rsc.MANUFACTURER))
        svc.addCharacteristic(readable(Rsc.MODEL))
        return svc
    }

    private fun addNextService() {
        val s = server ?: return
        val svc = pendingServices.removeFirstOrNull()
        if (svc == null) {
            startAdvertising()
            return
        }
        if (!s.addService(svc)) fail("Ajout du service ${svc.uuid} refusé.")
    }

    private fun valueFor(uuid: java.util.UUID): ByteArray? = when (uuid) {
        Rsc.FEATURE -> Rsc.FEATURE_VALUE
        Rsc.SENSOR_LOCATION -> Rsc.LOCATION_VALUE
        Rsc.BATTERY_LEVEL -> byteArrayOf(100)
        Rsc.MANUFACTURER -> "TapisSync".toByteArray()
        Rsc.MODEL -> "Footpod tapis".toByteArray()
        Rsc.MEASUREMENT -> currentMeasurement()
        else -> null
    }

    private fun currentMeasurement() = Rsc.measurement(speedKmh / 3.6, cadence, totalDistanceM)

    // ---------- Diffusion (advertising) ----------

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            main.post {
                advertising = true
                AppLog.add("Foulée", "Diffusion démarrée (nom : ${adapter?.name ?: "?"})")
                if (devices.isEmpty()) setState(State.ADVERTISING, null)
                lastTickMs = SystemClock.elapsedRealtime()
                main.removeCallbacks(tick)
                main.post(tick)
            }
        }

        override fun onStartFailure(errorCode: Int) {
            main.post {
                advertising = false
                val why = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "données trop longues"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "trop de diffusions en cours"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "déjà démarrée"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "non prise en charge par cet appareil"
                    else -> "erreur interne $errorCode"
                }
                fail("Diffusion impossible : $why.")
            }
        }
    }

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return fail("Diffusion Bluetooth indisponible.")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Paquet principal : le service « foulée » ; le nom de l'appareil va dans la réponse au scan.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(Rsc.SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        try {
            adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            fail("Diffusion impossible : ${e.message}")
        }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
    }

    // ---------- Envoi des mesures ----------

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            // Vitesse périmée (tapis déconnecté ou silencieux) : on envoie 0.
            if (now - lastDataMs > 4000) speedKmh = 0.0
            val dt = (now - lastTickMs) / 1000.0
            lastTickMs = now
            if (dt in 0.0..5.0) totalDistanceM += speedKmh / 3.6 * dt
            sendMeasurement()
            notifyListeners()
            main.postDelayed(this, 1000)
        }
    }

    @Suppress("DEPRECATION")
    private fun sendMeasurement() {
        val s = server ?: return
        val ch = measurement ?: return
        if (subscribed.isEmpty()) return
        val value = currentMeasurement()
        for (addr in subscribed.toList()) {
            val dev = devices[addr] ?: continue
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    s.notifyCharacteristicChanged(dev, ch, false, value)
                } else {
                    ch.value = value
                    s.notifyCharacteristicChanged(dev, ch, false)
                }
            } catch (e: Exception) {
                AppLog.add("Foulée", "Envoi impossible à $addr : ${e.message}")
            }
        }
    }

    // ---------- Données du tapis ----------

    override fun onData(data: Ftms.TreadmillData) {
        data.speedKmh?.let {
            speedKmh = it.coerceIn(0.0, 30.0)
            lastDataMs = SystemClock.elapsedRealtime()
        }
    }

    override fun onState(state: TreadmillManager.State, message: String?) {
        if (state != TreadmillManager.State.READY) speedKmh = 0.0
    }

    // ---------- Callback du serveur GATT ----------

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Service ${service.uuid} refusé (statut $status).")
                } else {
                    addNextService()
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (server == null) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    devices[device.address] = device
                    AppLog.add("Foulée", "Montre connectée : ${safeName(device) ?: device.address}")
                    setState(State.CONNECTED, null)
                } else {
                    devices.remove(device.address)
                    subscribed.remove(device.address)
                    AppLog.add("Foulée", "Montre déconnectée : ${safeName(device) ?: device.address}")
                    setState(if (devices.isEmpty()) State.ADVERTISING else State.CONNECTED, null)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            main.post {
                val s = server ?: return@post
                val value = valueFor(characteristic.uuid)
                if (value == null) {
                    s.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
                } else if (offset > value.size) {
                    s.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                } else {
                    s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            main.post {
                val s = server ?: return@post
                val on = descriptor.characteristic.uuid == Rsc.MEASUREMENT && subscribed.contains(device.address)
                val value = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            main.post {
                val s = server ?: return@post
                if (descriptor.uuid == Rsc.CCCD && descriptor.characteristic.uuid == Rsc.MEASUREMENT) {
                    val enable = value != null && value.isNotEmpty() && (value[0].toInt() and 0x03) != 0
                    if (enable) subscribed.add(device.address) else subscribed.remove(device.address)
                    AppLog.add("Foulée", "Mesures ${if (enable) "activées" else "désactivées"} par ${safeName(device) ?: device.address}")
                }
                if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            main.post {
                if (responseNeeded) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null)
                }
            }
        }
    }

    private fun safeName(d: BluetoothDevice): String? = try {
        d.name
    } catch (_: SecurityException) {
        null
    }
}

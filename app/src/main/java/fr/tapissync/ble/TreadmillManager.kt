package fr.tapissync.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import fr.tapissync.AppLog
import fr.tapissync.workout.MachineSpeedRange
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Connexion Bluetooth au tapis (FTMS). Tout tourne sur le fil principal :
 * les callbacks Bluetooth sont renvoyés sur le Handler principal.
 * Les opérations GATT passent une par une dans une file, comme l'exige Android.
 */
@SuppressLint("MissingPermission")
class TreadmillManager(context: Context) {

    enum class State(val label: String) {
        DISCONNECTED("Déconnecté"),
        CONNECTING("Connexion…"),
        DISCOVERING("Découverte des services…"),
        READY("Connecté"),
    }

    interface Listener {
        fun onState(state: State, message: String?) {}
        fun onData(data: Ftms.TreadmillData) {}
        fun onStatus(event: Ftms.StatusEvent) {}
        fun onResponse(response: Ftms.ControlResponse) {}
    }

    data class ScanItem(val address: String, val name: String, val rssi: Int, val hasFtms: Boolean)

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val listeners = CopyOnWriteArraySet<Listener>()

    var state: State = State.DISCONNECTED
        private set
    var stateMessage: String? = null
        private set
    var deviceName: String? = null
        private set
    var deviceAddress: String? = null
        private set
    var lastData: Ftms.TreadmillData? = null
        private set
    var features: Ftms.Features? = null
        private set
    var speedRange: MachineSpeedRange? = null
        private set
    var inclineRange: Triple<Double, Double, Double>? = null
        private set
    var hasControlPoint = false
        private set
    var lastResponse: Ftms.ControlResponse? = null
        private set

    /** Description des services trouvés, pour l'écran Diagnostic. */
    val discoveredServices = mutableListOf<String>()

    private var gatt: BluetoothGatt? = null
    private var wantConnected = false
    private var reconnectDelayMs = 2000L
    private var controlGranted = false

    // ---------- Permissions ----------

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ---------- Scan ----------

    private var scanCallback: ScanCallback? = null

    fun startScan(onResult: (ScanItem) -> Unit): String? {
        if (adapter == null) return "Bluetooth absent sur cet appareil."
        if (!adapter.isEnabled) return "Active le Bluetooth."
        if (!hasPermissions()) return "Autorisation Bluetooth manquante."
        val scanner = adapter.bluetoothLeScanner ?: return "Scanner Bluetooth indisponible."
        stopScan()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val uuids = result.scanRecord?.serviceUuids.orEmpty()
                val name = result.scanRecord?.deviceName ?: safeName(dev) ?: return
                val item = ScanItem(dev.address, name, result.rssi, uuids.contains(ParcelUuid(Ftms.SERVICE)))
                main.post { onResult(item) }
            }

            override fun onScanFailed(errorCode: Int) {
                AppLog.add("BLE", "Échec du scan (code $errorCode)")
            }
        }
        scanCallback = cb
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            scanner.startScan(null, settings, cb)
            AppLog.add("BLE", "Scan démarré")
            null
        } catch (e: Exception) {
            scanCallback = null
            "Scan impossible : ${e.message}"
        }
    }

    fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
    }

    private fun safeName(dev: BluetoothDevice): String? = try {
        dev.name
    } catch (_: SecurityException) {
        null
    }

    // ---------- Connexion ----------

    fun connect(address: String, name: String?) {
        stopScan()
        if (adapter == null || !hasPermissions()) {
            setState(State.DISCONNECTED, "Bluetooth indisponible ou autorisation manquante")
            return
        }
        closeGatt()
        wantConnected = true
        reconnectDelayMs = 2000L
        deviceAddress = address
        deviceName = name ?: deviceName
        openGatt(background = false)
    }

    /**
     * background = false : connexion directe (rapide, abandon au bout de ~30 s).
     * background = true : connexion « en veille », sans délai : Android se connecte dès que
     * le tapis est allumé, sans occuper la radio en continu.
     */
    private fun openGatt(background: Boolean) {
        val address = deviceAddress ?: return
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: run {
            setState(State.DISCONNECTED, "Adresse invalide")
            return
        }
        if (deviceName == null) deviceName = safeName(device)
        setState(State.CONNECTING, if (background) "en attente du tapis : allume-le" else null)
        AppLog.add("BLE", "${if (background) "En attente de" else "Connexion à"} ${deviceName ?: address}")
        gatt = device.connectGatt(ctx, background, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        wantConnected = false
        main.removeCallbacks(reconnectRunnable)
        closeGatt()
        setState(State.DISCONNECTED, null)
    }

    private fun closeGatt() {
        main.removeCallbacks(readyCheck)
        clearQueue()
        controlGranted = false
        hasControlPoint = false
        val g = gatt ?: return
        gatt = null
        try {
            g.disconnect()
            g.close()
        } catch (_: Exception) {
        }
    }

    private val reconnectRunnable = Runnable {
        if (wantConnected && state == State.DISCONNECTED) openGatt(background = true)
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, reconnectDelayMs)
    }

    private fun setState(s: State, msg: String?) {
        state = s
        stateMessage = msg
        listeners.forEach { it.onState(s, msg) }
    }

    // ---------- Commandes ----------

    /** Démarre la bande (avec prise de contrôle si besoin). */
    fun start() {
        ensureControl()
        enqueueCommand(Ftms.startOrResume(), Ftms.OP_START_RESUME)
    }

    fun pause() {
        ensureControl()
        enqueueCommand(Ftms.pause(), Ftms.OP_STOP_PAUSE)
    }

    fun stopBelt() {
        ensureControl()
        enqueueCommand(Ftms.stop(), Ftms.OP_STOP_PAUSE)
    }

    fun setSpeed(kmh: Double) {
        ensureControl()
        // Une seule consigne de vitesse en attente : la plus récente remplace l'ancienne.
        queue.removeAll { it is Op.Command && it.op == Ftms.OP_SET_SPEED }
        enqueueCommand(Ftms.setSpeed(kmh), Ftms.OP_SET_SPEED)
    }

    fun setIncline(pct: Double) {
        ensureControl()
        queue.removeAll { it is Op.Command && it.op == Ftms.OP_SET_INCLINE }
        enqueueCommand(Ftms.setIncline(pct), Ftms.OP_SET_INCLINE)
    }

    fun requestControl() {
        controlGranted = false
        ensureControl()
    }

    private fun ensureControl() {
        if (controlGranted) return
        val alreadyQueued = (current as? Op.Command)?.op == Ftms.OP_REQUEST_CONTROL ||
            queue.any { it is Op.Command && it.op == Ftms.OP_REQUEST_CONTROL }
        if (!alreadyQueued) enqueueCommand(Ftms.requestControl(), Ftms.OP_REQUEST_CONTROL)
    }

    private fun enqueueCommand(payload: ByteArray, op: Int) {
        if (state != State.READY || !hasControlPoint) {
            AppLog.add("BLE", "${Ftms.opName(op)} ignorée : tapis non prêt")
            return
        }
        enqueue(Op.Command(payload, op))
    }

    // ---------- File d'opérations GATT ----------

    private sealed class Op {
        data class Mtu(val size: Int) : Op()
        data class Read(val ch: UUID) : Op()
        data class Subscribe(val ch: UUID, val indicate: Boolean) : Op()
        class Command(val payload: ByteArray, val op: Int, var retried: Boolean = false) : Op()
    }

    private val queue = ArrayDeque<Op>()
    private var current: Op? = null
    private var commandWritten = false

    private val timeoutRunnable = Runnable {
        val op = current
        AppLog.add("BLE", "Délai dépassé pour ${describe(op)}")
        if (op is Op.Command && op.op == Ftms.OP_REQUEST_CONTROL) {
            // Certains tapis ne répondent pas à la prise de contrôle mais acceptent les commandes.
            controlGranted = true
        }
        completeCurrent()
    }

    private fun describe(op: Op?): String = when (op) {
        is Op.Command -> Ftms.opName(op.op)
        is Op.Read -> "lecture ${op.ch}"
        is Op.Subscribe -> "abonnement ${op.ch}"
        is Op.Mtu -> "MTU"
        null -> "—"
    }

    private fun enqueue(op: Op) {
        queue.addLast(op)
        if (current == null) next()
    }

    private fun clearQueue() {
        main.removeCallbacks(timeoutRunnable)
        queue.clear()
        current = null
        commandWritten = false
    }

    private fun completeCurrent() {
        main.removeCallbacks(timeoutRunnable)
        current = null
        commandWritten = false
        next()
    }

    private fun next() {
        if (current != null) return
        val g = gatt ?: return
        val op = queue.removeFirstOrNull() ?: return
        current = op
        main.postDelayed(timeoutRunnable, if (op is Op.Command) 3000L else 5000L)
        val ok = when (op) {
            is Op.Mtu -> g.requestMtu(op.size)
            is Op.Read -> characteristic(op.ch)?.let { g.readCharacteristic(it) } ?: false
            is Op.Subscribe -> subscribe(g, op.ch, op.indicate)
            is Op.Command -> writeControlPoint(g, op.payload)
        }
        if (!ok) {
            AppLog.add("BLE", "Échec immédiat : ${describe(op)}")
            completeCurrent()
        }
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(Ftms.SERVICE)?.getCharacteristic(uuid)

    @Suppress("DEPRECATION")
    private fun subscribe(g: BluetoothGatt, uuid: UUID, indicate: Boolean): Boolean {
        val ch = characteristic(uuid) ?: return false
        if (!g.setCharacteristicNotification(ch, true)) return false
        val desc = ch.getDescriptor(Ftms.CCCD) ?: return false
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(desc, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            desc.value = value
            g.writeDescriptor(desc)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeControlPoint(g: BluetoothGatt, payload: ByteArray): Boolean {
        val ch = characteristic(Ftms.CONTROL_POINT) ?: return false
        commandWritten = false
        AppLog.add("BLE", "→ ${Ftms.hex(payload)}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = payload
            g.writeCharacteristic(ch)
        }
    }

    // ---------- Réception ----------

    private fun onNotification(uuid: UUID, value: ByteArray) {
        when (uuid) {
            Ftms.TREADMILL_DATA -> Ftms.parseTreadmillData(value)?.let { d ->
                // Certains tapis découpent leurs données en plusieurs paquets :
                // on garde les champs déjà connus quand un paquet ne les contient pas.
                val prev = lastData
                val merged = if (prev == null) d else Ftms.TreadmillData(
                    speedKmh = d.speedKmh ?: prev.speedKmh,
                    avgSpeedKmh = d.avgSpeedKmh ?: prev.avgSpeedKmh,
                    distanceM = d.distanceM ?: prev.distanceM,
                    inclinePct = d.inclinePct ?: prev.inclinePct,
                    heartRate = d.heartRate ?: prev.heartRate,
                    elapsedS = d.elapsedS ?: prev.elapsedS,
                    kcal = d.kcal ?: prev.kcal,
                )
                lastData = merged
                listeners.forEach { it.onData(merged) }
            }
            Ftms.STATUS -> Ftms.parseStatus(value)?.let { ev ->
                AppLog.add("BLE", "Statut tapis : $ev (${Ftms.hex(value)})")
                if (ev is Ftms.StatusEvent.ControlLost || ev is Ftms.StatusEvent.Reset) controlGranted = false
                listeners.forEach { it.onStatus(ev) }
            }
            Ftms.CONTROL_POINT -> Ftms.parseControlResponse(value)?.let { onControlResponse(it) }
        }
    }

    private fun onControlResponse(r: Ftms.ControlResponse) {
        AppLog.add("BLE", "← $r")
        lastResponse = r
        val op = current as? Op.Command
        if (r.requestOp == Ftms.OP_REQUEST_CONTROL) {
            // Même en cas de refus, on tente les commandes : certains tapis répondent
            // « non pris en charge » à la prise de contrôle mais obéissent ensuite.
            controlGranted = true
        }
        if (!r.ok && r.result == 0x05 && op != null && op.op == r.requestOp && !op.retried) {
            // Contrôle refusé : on redemande le contrôle puis on rejoue la commande une fois.
            op.retried = true
            controlGranted = false
            queue.addFirst(Op.Command(op.payload, op.op, retried = true))
            queue.addFirst(Op.Command(Ftms.requestControl(), Ftms.OP_REQUEST_CONTROL))
        }
        listeners.forEach { it.onResponse(r) }
        if (op != null && op.op == r.requestOp) completeCurrent()
    }

    private fun onReady() {
        setState(State.READY, null)
        reconnectDelayMs = 2000L
    }

    // ---------- Callback GATT ----------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) {
                    try { g.close() } catch (_: Exception) {}
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    AppLog.add("BLE", "Connecté, découverte des services")
                    setState(State.DISCOVERING, null)
                    if (!g.discoverServices()) {
                        AppLog.add("BLE", "discoverServices a échoué")
                    }
                } else {
                    AppLog.add("BLE", "Déconnecté (statut $status, état $newState)")
                    closeGatt()
                    setState(State.DISCONNECTED, if (status != 0) statusText(status) else null)
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                discoveredServices.clear()
                for (s in g.services) {
                    discoveredServices += "Service ${s.uuid}"
                    for (c in s.characteristics) {
                        discoveredServices += "   • ${c.uuid} (${props(c.properties)})"
                    }
                }
                AppLog.add("BLE", "Services : ${g.services.joinToString { it.uuid.toString().substring(4, 8) }}")
                val ftms = g.getService(Ftms.SERVICE)
                if (ftms == null) {
                    AppLog.add("BLE", "Service FTMS absent")
                    setState(State.READY, "Service FTMS introuvable : envoie le diagnostic")
                    return@post
                }
                hasControlPoint = ftms.getCharacteristic(Ftms.CONTROL_POINT) != null
                enqueue(Op.Mtu(185))
                if (ftms.getCharacteristic(Ftms.TREADMILL_DATA) != null) enqueue(Op.Subscribe(Ftms.TREADMILL_DATA, false))
                if (ftms.getCharacteristic(Ftms.STATUS) != null) enqueue(Op.Subscribe(Ftms.STATUS, false))
                if (hasControlPoint) enqueue(Op.Subscribe(Ftms.CONTROL_POINT, true))
                if (ftms.getCharacteristic(Ftms.FEATURE) != null) enqueue(Op.Read(Ftms.FEATURE))
                if (ftms.getCharacteristic(Ftms.SPEED_RANGE) != null) enqueue(Op.Read(Ftms.SPEED_RANGE))
                if (ftms.getCharacteristic(Ftms.INCLINE_RANGE) != null) enqueue(Op.Read(Ftms.INCLINE_RANGE))
                // Marqueur de fin d'initialisation : traité quand la file se vide.
                main.postDelayed(readyCheck, 300)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                AppLog.add("BLE", "MTU $mtu (statut $status)")
                if (current is Op.Mtu) completeCurrent()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    AppLog.add("BLE", "Abonnement refusé pour ${descriptor.characteristic.uuid} (statut $status)")
                }
                if (current is Op.Subscribe) completeCurrent()
            }
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            val value = ch.value ?: ByteArray(0)
            main.post { handleRead(ch.uuid, value, status) }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            main.post { handleRead(ch.uuid, value, status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post {
                val op = current as? Op.Command ?: return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    AppLog.add("BLE", "Écriture refusée (${Ftms.opName(op.op)}, statut $status)")
                    if (op.op == Ftms.OP_REQUEST_CONTROL) controlGranted = true
                    completeCurrent()
                } else {
                    commandWritten = true
                    // On attend maintenant la réponse du tapis (indication), ou le délai.
                }
            }
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val value = ch.value ?: return
            val copy = value.copyOf()
            main.post { onNotification(ch.uuid, copy) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            main.post { onNotification(ch.uuid, copy) }
        }
    }

    private val readyCheck = object : Runnable {
        override fun run() {
            if (gatt == null || state != State.DISCOVERING) return
            if (current == null && queue.isEmpty()) {
                onReady()
            } else {
                main.postDelayed(this, 200)
            }
        }
    }

    private fun handleRead(uuid: UUID, value: ByteArray, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            when (uuid) {
                Ftms.FEATURE -> {
                    features = Ftms.parseFeatures(value)
                    AppLog.add("BLE", "Fonctions : ${Ftms.hex(value)} → vitesse pilotable=${features?.speedTarget}, inclinaison=${features?.inclineTarget}")
                }
                Ftms.SPEED_RANGE -> Ftms.parseSpeedRange(value)?.let {
                    speedRange = MachineSpeedRange(it.first, it.second, it.third)
                    AppLog.add("BLE", "Plage de vitesse : ${it.first}–${it.second} km/h, pas ${it.third}")
                }
                Ftms.INCLINE_RANGE -> Ftms.parseInclineRange(value)?.let {
                    inclineRange = it
                    AppLog.add("BLE", "Plage d'inclinaison : ${it.first}–${it.second} %, pas ${it.third}")
                }
            }
        } else {
            AppLog.add("BLE", "Lecture $uuid refusée (statut $status)")
        }
        if (current is Op.Read) completeCurrent()
    }

    private fun statusText(status: Int): String = when (status) {
        8 -> "signal perdu (code 8)"
        19 -> "déconnecté par le tapis (code 19)"
        22 -> "déconnecté par la tablette (code 22)"
        62 -> "connexion impossible (code 62)"
        133 -> "erreur Bluetooth (code 133)"
        147 -> "tapis introuvable : éteint ou hors de portée (code 147)"
        else -> "connexion perdue (code $status)"
    }

    private fun props(p: Int): String = buildList {
        if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) add("lecture")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) add("écriture")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) add("écriture sans réponse")
        if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) add("notification")
        if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) add("indication")
    }.joinToString(", ")
}

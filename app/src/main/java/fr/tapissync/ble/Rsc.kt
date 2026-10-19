package fr.tapissync.ble

import java.util.UUID

/**
 * Bluetooth RSC (Running Speed and Cadence, 0x1814) : le profil des capteurs de foulée.
 * Encodage des valeurs envoyées à la montre. Aucune dépendance Android, pour les tests.
 */
object Rsc {
    private fun uuid16(short: Int): UUID =
        UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    val SERVICE: UUID = uuid16(0x1814)
    val MEASUREMENT: UUID = uuid16(0x2A53)
    val FEATURE: UUID = uuid16(0x2A54)
    val SENSOR_LOCATION: UUID = uuid16(0x2A5D)

    val BATTERY_SERVICE: UUID = uuid16(0x180F)
    val BATTERY_LEVEL: UUID = uuid16(0x2A19)

    val DEVICE_INFO_SERVICE: UUID = uuid16(0x180A)
    val MANUFACTURER: UUID = uuid16(0x2A29)
    val MODEL: UUID = uuid16(0x2A24)

    val CCCD: UUID = uuid16(0x2902)

    /** Fonctions annoncées : distance totale (bit 1) et course/marche (bit 2). */
    val FEATURE_VALUE = byteArrayOf(0x06, 0x00)

    /** Emplacement du capteur : « dans la chaussure ». */
    val LOCATION_VALUE = byteArrayOf(0x02)

    /** Au-delà de cette vitesse, on signale « course » plutôt que « marche ». */
    const val RUNNING_THRESHOLD_KMH = 7.0

    /**
     * Mesure RSC : drapeaux, vitesse (1/256 m/s), cadence (par minute), distance totale (1/10 m).
     */
    fun measurement(speedMps: Double, cadence: Int, totalDistanceM: Double): ByteArray {
        val running = speedMps * 3.6 >= RUNNING_THRESHOLD_KMH
        val flags = 0x02 or (if (running) 0x04 else 0x00)
        val speed = Math.round(speedMps.coerceAtLeast(0.0) * 256).toInt().coerceIn(0, 0xFFFF)
        val cad = cadence.coerceIn(0, 0xFF)
        val dist = Math.round(totalDistanceM.coerceAtLeast(0.0) * 10).coerceIn(0L, 0xFFFFFFFFL)
        return byteArrayOf(
            flags.toByte(),
            (speed and 0xFF).toByte(), ((speed shr 8) and 0xFF).toByte(),
            cad.toByte(),
            (dist and 0xFF).toByte(), ((dist shr 8) and 0xFF).toByte(),
            ((dist shr 16) and 0xFF).toByte(), ((dist shr 24) and 0xFF).toByte(),
        )
    }
}

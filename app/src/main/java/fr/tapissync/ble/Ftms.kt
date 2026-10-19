package fr.tapissync.ble

import java.util.UUID

/**
 * Bluetooth FTMS (Fitness Machine Service, 0x1826) : constantes, décodage des données
 * du tapis et encodage des commandes. Aucune dépendance Android ici, pour pouvoir le tester.
 */
object Ftms {
    private fun uuid16(short: Int): UUID =
        UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    val SERVICE: UUID = uuid16(0x1826)
    val FEATURE: UUID = uuid16(0x2ACC)
    val TREADMILL_DATA: UUID = uuid16(0x2ACD)
    val TRAINING_STATUS: UUID = uuid16(0x2AD3)
    val SPEED_RANGE: UUID = uuid16(0x2AD4)
    val INCLINE_RANGE: UUID = uuid16(0x2AD5)
    val CONTROL_POINT: UUID = uuid16(0x2AD9)
    val STATUS: UUID = uuid16(0x2ADA)
    val CCCD: UUID = uuid16(0x2902)

    // Commandes du point de contrôle
    const val OP_REQUEST_CONTROL = 0x00
    const val OP_RESET = 0x01
    const val OP_SET_SPEED = 0x02
    const val OP_SET_INCLINE = 0x03
    const val OP_START_RESUME = 0x07
    const val OP_STOP_PAUSE = 0x08
    const val OP_RESPONSE = 0x80

    const val RESULT_SUCCESS = 0x01

    fun opName(op: Int): String = when (op) {
        OP_REQUEST_CONTROL -> "Prise de contrôle"
        OP_RESET -> "Réinitialisation"
        OP_SET_SPEED -> "Vitesse"
        OP_SET_INCLINE -> "Inclinaison"
        OP_START_RESUME -> "Démarrage"
        OP_STOP_PAUSE -> "Arrêt/pause"
        else -> "Commande 0x%02X".format(op)
    }

    fun resultName(code: Int): String = when (code) {
        0x01 -> "OK"
        0x02 -> "non prise en charge"
        0x03 -> "paramètre invalide"
        0x04 -> "échec"
        0x05 -> "contrôle refusé"
        else -> "code 0x%02X".format(code)
    }

    fun requestControl() = byteArrayOf(OP_REQUEST_CONTROL.toByte())
    fun reset() = byteArrayOf(OP_RESET.toByte())
    fun startOrResume() = byteArrayOf(OP_START_RESUME.toByte())
    fun stop() = byteArrayOf(OP_STOP_PAUSE.toByte(), 0x01)
    fun pause() = byteArrayOf(OP_STOP_PAUSE.toByte(), 0x02)

    /** Vitesse cible, résolution 0,01 km/h. */
    fun setSpeed(kmh: Double): ByteArray {
        val v = Math.round(kmh * 100).toInt().coerceIn(0, 0xFFFF)
        return byteArrayOf(OP_SET_SPEED.toByte(), (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }

    /** Inclinaison cible, résolution 0,1 %. */
    fun setIncline(percent: Double): ByteArray {
        val v = Math.round(percent * 10).toInt().coerceIn(-32768, 32767)
        return byteArrayOf(OP_SET_INCLINE.toByte(), (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }

    data class ControlResponse(val requestOp: Int, val result: Int) {
        val ok: Boolean get() = result == RESULT_SUCCESS
        override fun toString() = "${opName(requestOp)} : ${resultName(result)}"
    }

    fun parseControlResponse(b: ByteArray): ControlResponse? {
        if (b.size < 3 || (b[0].toInt() and 0xFF) != OP_RESPONSE) return null
        return ControlResponse(b[1].toInt() and 0xFF, b[2].toInt() and 0xFF)
    }

    /** Données instantanées du tapis. Les champs absents restent à null. */
    data class TreadmillData(
        val speedKmh: Double? = null,
        val avgSpeedKmh: Double? = null,
        val distanceM: Int? = null,
        val inclinePct: Double? = null,
        val heartRate: Int? = null,
        val elapsedS: Int? = null,
        val kcal: Int? = null,
    )

    fun parseTreadmillData(b: ByteArray): TreadmillData? {
        if (b.size < 2) return null
        val r = Reader(b)
        val flags = r.u16() ?: return null
        var speed: Double? = null
        var avg: Double? = null
        var dist: Int? = null
        var incline: Double? = null
        var hr: Int? = null
        var elapsed: Int? = null
        var kcal: Int? = null

        // Bit 0 « More Data » : à 0, la vitesse instantanée est présente.
        if ((flags and 0x0001) == 0) speed = r.u16()?.let { it / 100.0 }
        if ((flags and 0x0002) != 0) avg = r.u16()?.let { it / 100.0 }
        if ((flags and 0x0004) != 0) dist = r.u24()
        if ((flags and 0x0008) != 0) {
            incline = r.s16()?.let { it / 10.0 }
            r.skip(2) // angle de rampe
        }
        if ((flags and 0x0010) != 0) r.skip(4) // dénivelés + et −
        if ((flags and 0x0020) != 0) r.skip(1) // allure instantanée
        if ((flags and 0x0040) != 0) r.skip(1) // allure moyenne
        if ((flags and 0x0080) != 0) {
            kcal = r.u16()
            r.skip(3) // kcal/h, kcal/min
        }
        if ((flags and 0x0100) != 0) hr = r.u8()
        if ((flags and 0x0200) != 0) r.skip(1) // MET
        if ((flags and 0x0400) != 0) elapsed = r.u16()
        // Les champs suivants (temps restant, force, puissance) ne servent pas ici.

        return TreadmillData(
            speedKmh = speed,
            avgSpeedKmh = avg,
            distanceM = dist,
            inclinePct = incline,
            heartRate = hr?.takeIf { it in 30..250 },
            elapsedS = elapsed,
            kcal = kcal?.takeIf { it < 0xFFFF },
        )
    }

    /** Événements « Fitness Machine Status » utiles à la séance. */
    sealed class StatusEvent {
        object Reset : StatusEvent()
        data class StoppedByUser(val paused: Boolean) : StatusEvent()
        object SafetyKey : StatusEvent()
        object StartedByUser : StatusEvent()
        data class TargetSpeedChanged(val kmh: Double) : StatusEvent()
        data class TargetInclineChanged(val pct: Double) : StatusEvent()
        object ControlLost : StatusEvent()
        data class Other(val op: Int) : StatusEvent()
    }

    fun parseStatus(b: ByteArray): StatusEvent? {
        if (b.isEmpty()) return null
        val r = Reader(b)
        return when (val op = r.u8() ?: return null) {
            0x01 -> StatusEvent.Reset
            0x02 -> StatusEvent.StoppedByUser(paused = r.u8() == 0x02)
            0x03 -> StatusEvent.SafetyKey
            0x04 -> StatusEvent.StartedByUser
            0x05 -> r.u16()?.let { StatusEvent.TargetSpeedChanged(it / 100.0) } ?: StatusEvent.Other(op)
            0x06 -> r.s16()?.let { StatusEvent.TargetInclineChanged(it / 10.0) } ?: StatusEvent.Other(op)
            0xFF -> StatusEvent.ControlLost
            else -> StatusEvent.Other(op)
        }
    }

    data class Features(val speedTarget: Boolean, val inclineTarget: Boolean, val raw: String)

    fun parseFeatures(b: ByteArray): Features? {
        if (b.size < 8) return null
        val r = Reader(b)
        r.skip(4)
        val target = r.u32() ?: return null
        return Features(
            speedTarget = (target and 0x1L) != 0L,
            inclineTarget = (target and 0x2L) != 0L,
            raw = hex(b),
        )
    }

    /** Plage de vitesse : min, max, incrément (km/h). */
    fun parseSpeedRange(b: ByteArray): Triple<Double, Double, Double>? {
        if (b.size < 6) return null
        val r = Reader(b)
        val min = r.u16() ?: return null
        val max = r.u16() ?: return null
        val inc = r.u16() ?: return null
        return Triple(min / 100.0, max / 100.0, inc / 100.0)
    }

    /** Plage d'inclinaison : min, max, incrément (%). */
    fun parseInclineRange(b: ByteArray): Triple<Double, Double, Double>? {
        if (b.size < 6) return null
        val r = Reader(b)
        val min = r.s16() ?: return null
        val max = r.s16() ?: return null
        val inc = r.u16() ?: return null
        return Triple(min / 10.0, max / 10.0, inc / 10.0)
    }

    fun hex(b: ByteArray?): String = b?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: "—"

    private class Reader(private val b: ByteArray) {
        private var i = 0
        fun skip(n: Int) { i += n }
        fun u8(): Int? = if (i + 1 <= b.size) (b[i].toInt() and 0xFF).also { i += 1 } else null
        fun u16(): Int? = if (i + 2 <= b.size) {
            ((b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)).also { i += 2 }
        } else null
        fun s16(): Int? = u16()?.let { if (it >= 0x8000) it - 0x10000 else it }
        fun u24(): Int? = if (i + 3 <= b.size) {
            ((b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
                ((b[i + 2].toInt() and 0xFF) shl 16)).also { i += 3 }
        } else null
        fun u32(): Long? = if (i + 4 <= b.size) {
            ((b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
                ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)).also { i += 4 }
        } else null
    }
}

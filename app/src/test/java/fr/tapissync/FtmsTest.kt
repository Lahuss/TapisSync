package fr.tapissync

import fr.tapissync.ble.Ftms
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtmsTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun encodesSpeedCommand() {
        // 12,6 km/h = 1260 = 0x04EC, petit-boutiste
        assertArrayEquals(bytes(0x02, 0xEC, 0x04), Ftms.setSpeed(12.6))
        assertArrayEquals(bytes(0x02, 0x00, 0x00), Ftms.setSpeed(0.0))
        assertArrayEquals(bytes(0x03, 0x0F, 0x00), Ftms.setIncline(1.5))
        assertArrayEquals(bytes(0x03, 0xF6, 0xFF), Ftms.setIncline(-1.0))
        assertArrayEquals(bytes(0x08, 0x02), Ftms.pause())
    }

    @Test
    fun parsesTreadmillData() {
        // Drapeaux 0x040C : distance + inclinaison + temps écoulé, vitesse présente (bit 0 à 0).
        val data = bytes(
            0x0C, 0x04,       // flags
            0xEC, 0x04,       // vitesse 12,60 km/h
            0x10, 0x27, 0x00, // distance 10000 m
            0x0F, 0x00,       // inclinaison 1,5 %
            0x00, 0x00,       // angle de rampe
            0x2C, 0x01,       // temps écoulé 300 s
        )
        val d = Ftms.parseTreadmillData(data)
        assertNotNull(d)
        d!!
        assertEquals(12.6, d.speedKmh!!, 1e-9)
        assertEquals(10000, d.distanceM)
        assertEquals(1.5, d.inclinePct!!, 1e-9)
        assertEquals(300, d.elapsedS)
        assertNull(d.heartRate)
    }

    @Test
    fun parsesTreadmillDataWithHeartRateAndEnergy() {
        // Drapeaux 0x0184 : distance + énergie + fréquence cardiaque.
        val data = bytes(
            0x84, 0x01,
            0xF4, 0x01,       // 5,00 km/h
            0xE8, 0x03, 0x00, // 1000 m
            0x32, 0x00,       // 50 kcal
            0xFF, 0xFF, 0xFF, // kcal/h, kcal/min non dispo
            0x8C,             // 140 bpm
        )
        val d = Ftms.parseTreadmillData(data)!!
        assertEquals(5.0, d.speedKmh!!, 1e-9)
        assertEquals(1000, d.distanceM)
        assertEquals(50, d.kcal)
        assertEquals(140, d.heartRate)
    }

    @Test
    fun truncatedDataDoesNotCrash() {
        val d = Ftms.parseTreadmillData(bytes(0x0C, 0x04, 0xEC))
        assertNotNull(d)
        assertNull(d!!.speedKmh)
        assertNull(Ftms.parseTreadmillData(bytes(0x01)))
    }

    @Test
    fun parsesResponsesAndStatus() {
        val r = Ftms.parseControlResponse(bytes(0x80, 0x02, 0x01))!!
        assertEquals(Ftms.OP_SET_SPEED, r.requestOp)
        assertTrue(r.ok)
        assertFalse(Ftms.parseControlResponse(bytes(0x80, 0x00, 0x05))!!.ok)
        assertNull(Ftms.parseControlResponse(bytes(0x02, 0x00)))

        assertEquals(Ftms.StatusEvent.StoppedByUser(paused = true), Ftms.parseStatus(bytes(0x02, 0x02)))
        assertEquals(Ftms.StatusEvent.SafetyKey, Ftms.parseStatus(bytes(0x03)))
        assertEquals(Ftms.StatusEvent.TargetSpeedChanged(12.6), Ftms.parseStatus(bytes(0x05, 0xEC, 0x04)))
    }

    @Test
    fun parsesFeaturesAndRanges() {
        val f = Ftms.parseFeatures(bytes(0x0F, 0x14, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00))!!
        assertTrue(f.speedTarget)
        assertTrue(f.inclineTarget)
        val speed = Ftms.parseSpeedRange(bytes(0x64, 0x00, 0xD0, 0x07, 0x0A, 0x00))!!
        assertEquals(1.0, speed.first, 1e-9)
        assertEquals(20.0, speed.second, 1e-9)
        assertEquals(0.1, speed.third, 1e-9)
        val incl = Ftms.parseInclineRange(bytes(0x00, 0x00, 0x96, 0x00, 0x0A, 0x00))!!
        assertEquals(15.0, incl.second, 1e-9)
    }
}

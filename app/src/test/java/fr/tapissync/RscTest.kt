package fr.tapissync

import fr.tapissync.ble.Rsc
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RscTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun encodesRunningMeasurement() {
        // 12,6 km/h = 3,5 m/s = 896/256 ; cadence 170 ; 1234,5 m = 12345 dixièmes
        val m = Rsc.measurement(3.5, 170, 1234.5)
        assertArrayEquals(
            bytes(0x06, 0x80, 0x03, 0xAA, 0x39, 0x30, 0x00, 0x00),
            m,
        )
    }

    @Test
    fun walkingAndLimits() {
        // 5 km/h : statut « marche » (bit 2 à 0), cadence nulle
        val m = Rsc.measurement(5.0 / 3.6, 0, 0.0)
        assertEquals(0x02, m[0].toInt())
        assertEquals(0, m[3].toInt())
        // Valeurs négatives ou trop grandes bornées
        val z = Rsc.measurement(-1.0, 400, -5.0)
        assertArrayEquals(bytes(0x02, 0x00, 0x00, 0xFF, 0x00, 0x00, 0x00, 0x00), z)
    }
}

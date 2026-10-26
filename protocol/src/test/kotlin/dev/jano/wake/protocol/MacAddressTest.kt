package dev.jano.wake.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacAddressTest {
    private val expected = MacAddress("0A:1B:2C:3D:4E:5F")

    @Test
    fun parsesColonDashAndBare() {
        assertEquals(expected, parseMac("0a:1b:2c:3d:4e:5f"))
        assertEquals(expected, parseMac("0A-1B-2C-3D-4E-5F"))
        assertEquals(expected, parseMac("0a1b2c3d4e5f"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals(expected, parseMac(" 0A:1B:2C:3D:4E:5F "))
    }

    @Test
    fun rejectsInvalid() {
        assertNull(parseMac("0A:1B:2C:3D:4E"))
        assertNull(parseMac("GG:1B:2C:3D:4E:5F"))
        assertNull(parseMac(""))
        assertNull(parseMac("0A:1B:2C:3D:4E:5F:11"))
    }

    @Test
    fun bytesMatchValue() {
        assertEquals(
            listOf(0x0A, 0x1B, 0x2C, 0x3D, 0x4E, 0x5F),
            expected.bytes().map { it.toInt() and 0xFF },
        )
    }
}

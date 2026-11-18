package app.switchboard.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStatusTest {
    @Test fun parsesMeminfo() {
        val m = MemoryStatus.parse("""
            MemTotal:       11534336 kB
            MemFree:          402116 kB
            MemAvailable:    4718592 kB
            Buffers:            1024 kB
            SwapTotal:       8388608 kB
            SwapFree:        6291456 kB
        """.trimIndent())
        assertEquals(11534336L * 1024, m.totalRam)
        assertEquals(4718592L * 1024, m.freeRam)          // MemAvailable, not MemFree
        assertEquals(8L shl 30, m.swapTotal); assertEquals(2L shl 30, m.swapUsed)
        assertTrue(MemoryStatus.read() != null)           // this machine's /proc/meminfo
    }
}

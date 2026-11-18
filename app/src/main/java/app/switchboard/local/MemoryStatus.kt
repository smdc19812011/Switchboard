package app.switchboard.local

import java.io.File

/**
 * The phone's memory right now: free RAM and swap. On Samsung phones swap is mostly "RAM Plus" (virtual memory on
 * storage): it doesn't make a model faster, but lets Android move other apps out of RAM, leaving more for the model.
 */
data class MemoryStatus(val totalRam: Long, val freeRam: Long, val swapTotal: Long, val swapFree: Long) {
    val swapUsed get() = (swapTotal - swapFree).coerceAtLeast(0)

    companion object {
        /** Reads /proc/meminfo (values in kB). [freeRam] uses MemAvailable, what the system could give an app now. */
        fun parse(meminfo: String): MemoryStatus {
            val kv = meminfo.lines().mapNotNull { l ->
                val k = l.substringBefore(':', "").trim(); val v = l.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull()
                if (k.isEmpty() || v == null) null else k to v * 1024
            }.toMap()
            return MemoryStatus(kv["MemTotal"] ?: 0, kv["MemAvailable"] ?: kv["MemFree"] ?: 0, kv["SwapTotal"] ?: 0, kv["SwapFree"] ?: 0)
        }

        fun read(): MemoryStatus? = runCatching { parse(File("/proc/meminfo").readText()) }.getOrNull()
    }
}

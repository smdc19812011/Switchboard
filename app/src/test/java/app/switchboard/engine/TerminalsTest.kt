package app.switchboard.engine

import app.switchboard.net.Runner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket

class TerminalsTest {
    @Test fun cleanStripsColorsAndRedraws() {
        assertEquals("hello world", TerminalHub.clean("\u001B[1;32mhello\u001B[0m world"))
        assertEquals("100%", TerminalHub.clean("10%\r50%\r100%"))
        assertEquals("ab", TerminalHub.clean("abc\b"))
        assertEquals("title gone", TerminalHub.clean("\u001B]0;my title\u0007title gone"))
        assertEquals("line1\nline2", TerminalHub.clean("line1\r\nline2"))
    }

    @Test fun keyNamesBecomeBytes() {
        assertEquals("y\r", String(TerminalHub.keysToBytes("y{ENTER}")))
        assertEquals("\u0003", String(TerminalHub.keysToBytes("{CTRL+C}")))
        assertEquals("\u001B[A\t\u001B", String(TerminalHub.keysToBytes("{UP}{TAB}{ESC}")))
        assertEquals("{notakey}", String(TerminalHub.keysToBytes("{notakey}")))
        assertEquals(":wq\r", String(TerminalHub.keysToBytes("{ESC}:wq{ENTER}")).drop(1))
    }

    companion object {
        private lateinit var proc: Process
        private lateinit var workspace: File
        private var port = 0

        @BeforeClass @JvmStatic fun start() {
            port = ServerSocket(0).use { it.localPort }
            workspace = File.createTempFile("term-ws", "").apply { delete(); mkdirs() }
            proc = ProcessBuilder("python3", File("src/main/assets/runner.py").absolutePath, "--port", "$port", "--token", "t", "--workspace", workspace.path)
                .redirectErrorStream(true).start()
            assertTrue(proc.inputStream.bufferedReader().readLine().orEmpty().contains("ready"))
            Thread { proc.inputStream.copyTo(java.io.OutputStream.nullOutputStream()) }.apply { isDaemon = true }.start()
        }

        @AfterClass @JvmStatic fun stop() { proc.destroy(); workspace.deleteRecursively() }
    }

    /** An interactive prompt answered through the hub, as the model would with terminal_start / terminal_send. */
    @Test fun promptRoundTripThroughRunner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var changes = 0
        val hub = TerminalHub(scope) { changes++ }
        try {
            withTimeout(30_000) {
                val runner = Runner("http://127.0.0.1:$port", "t", unrestricted = false)
                val t = hub.start(runner, "printf 'Name? '; read n; echo \"hi \$n\"; stty size; read x", "greet", cols = 90, rows = 25)
                hub.awaitOutput(t.id, 5000)
                assertTrue(hub.modelView(t.id), hub.modelView(t.id).contains("Name?"))
                hub.send(t.id, TerminalHub.keysToBytes("Bob{ENTER}"))
                hub.awaitOutput(t.id, 5000)
                val v = hub.modelView(t.id)
                assertTrue(v, v.contains("hi Bob") && v.contains("25 90"))
                assertTrue(hub.modelView(t.id).contains("No new output"))
                val (snap, upTo) = hub.snapshot(t.id)
                assertTrue(String(snap).contains("hi Bob")); assertEquals(snap.size.toLong(), upTo)
                hub.send(t.id, "\r".toByteArray())
                while (hub.list.first().alive) delay(100)
                assertFalse(hub.list.first().alive); assertEquals(0, hub.list.first().exitCode)
                hub.close(t.id)
                assertTrue(hub.list.isEmpty() && changes >= 3)
            }
        } finally { scope.cancel() }
    }
}

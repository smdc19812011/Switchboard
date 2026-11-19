package app.switchboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HereticTest {
    private fun res(name: String) = File("src/test/resources/$name").readText()

    /** On Heretic's real config files: the two options change in place at the top level; presets without them get them added before any table. */
    @Test fun patchesRealConfigs() {
        val def = Heretic.patchConfig(res("heretic-config.default.toml"), quant4bit = true, trials = 40)
        assertTrue(Regex("(?m)^quantization = \"bnb_4bit\"$").containsMatchIn(def))
        assertTrue(Regex("(?m)^n_trials = 40$").containsMatchIn(def))
        assertEquals(1, Regex("(?m)^quantization\\s*=").findAll(def).count())
        val slop = Heretic.patchConfig(res("heretic-config.noslop.toml"), quant4bit = false, trials = 100)
        val firstTable = slop.indexOf("\n[")
        assertTrue(slop.indexOf("quantization = \"none\"") in 0 until firstTable && slop.indexOf("n_trials = 100") in 0 until firstTable)
        assertTrue(slop.contains("score_name = \"Responses with slop\"")) // the rest is untouched
    }

    @Test fun commands() {
        assertEquals("Qwen3-4B-Instruct-2507-heretic", Heretic.outName("Qwen/Qwen3-4B-Instruct-2507"))
        val run = Heretic.runCommand("Qwen/Qwen3-4B-Instruct-2507", "config.toml", unattended = true)
        assertEquals("HERETIC_CONFIG_FILE='config.toml' heretic --model 'Qwen/Qwen3-4B-Instruct-2507' --checkpoint-action continue " +
            "--trial-index 0 --model-action save --export-strategy merge --save-directory 'out/Qwen3-4B-Instruct-2507-heretic'", run)
        assertTrue(!Heretic.runCommand("m", "c.toml", unattended = false).contains("--model-action"))
        assertEquals("'it'\\''s'", Heretic.q("it's"))
        assertEquals(Heretic.goals.first(), Heretic.goal("nope"))
        assertEquals(Heretic.toolNames, Heretic.tools.map { it.name }.toSet())
    }

    /** The helper scripts run as written (the check without PyTorch here; the export's guard for a missing model). */
    @Test fun scriptsRun() {
        val dir = File.createTempFile("heretic", "").apply { delete(); mkdirs() }
        try {
            File(dir, "check.py").writeText(Heretic.checkScript)
            val check = ProcessBuilder("python3", "check.py").directory(dir).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
            assertTrue(check, check.startsWith("HERETIC_CHECK {") && check.contains("\"heretic\": false"))
            File(dir, "export.py").writeText(Heretic.exportScript)
            assertEquals(0, ProcessBuilder("python3", "-m", "py_compile", "export.py").directory(dir).start().waitFor()) // the whole script parses
            val p = ProcessBuilder("python3", "export.py", "out/missing-heretic", "q8_0").directory(dir).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue(out, p.waitFor() != 0 && out.contains("No saved model in out/missing-heretic"))
        } finally { dir.deleteRecursively() }
    }
}

package app.switchboard.engine

import app.switchboard.net.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Heretic (github.com/p-e-w/heretic, AGPL-3.0): training-free modification of language models (removing refusals,
 * reducing slop or sycophancy, ...). It needs Python, PyTorch and ideally a GPU, so it runs on the user's computer
 * through the Switchboard runner: installed from PyPI there, not bundled. The results can be converted to GGUF and
 * loaded on the phone as a local model. This object only builds commands and scripts (pure; unit-tested).
 */
object Heretic {
    data class Goal(val id: String, val label: String, val configFile: String?, val description: String)

    val goals = listOf(
        Goal("decensor", "Remove refusals", null, "Heretic's default: suppresses refusals while keeping the model's abilities"),
        Goal("noslop", "Reduce slop", "config.noslop.toml", "fewer clichés and purple prose"),
        Goal("nosyco", "Reduce sycophancy", "config.nosyco.toml", "less flattery and automatic agreement"),
        Goal("terse", "Shorter answers", "config.terse.toml", "more concise responses"),
        Goal("nohumor", "Reduce humor", "config.nohumor.toml", "drier, more serious tone"),
        Goal("emotions", "Change emotional tone", "config.emotions.toml", "alters emotional alignment"),
    )

    fun goal(id: String?) = goals.firstOrNull { it.id == id } ?: goals.first()

    const val CONFIG_BASE = "https://raw.githubusercontent.com/p-e-w/heretic/master/"
    /** Everything lives under this folder of the runner workspace. */
    const val DIR = "heretic"

    /** Shell-quotes for sh/bash (Linux, macOS, Termux). */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Folder (inside [DIR]) the processed model is saved to. */
    fun outName(model: String) = model.trimEnd('/').substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_") + "-heretic"

    /**
     * Changes the options Switchboard controls in a Heretic config file (TOML). Only top-level keys are touched: they
     * must stay before the first [table] header, so missing ones are added at the top.
     */
    fun patchConfig(toml: String, quant4bit: Boolean, trials: Int): String {
        val header = Regex("(?m)^\\s*\\[").find(toml)?.range?.first ?: toml.length
        var head = toml.substring(0, header); val rest = toml.substring(header)
        fun set(key: String, value: String) {
            val re = Regex("(?m)^$key\\s*=.*$")
            head = if (re.containsMatchIn(head)) head.replaceFirst(re, "$key = $value") else "$key = $value\n$head"
        }
        set("quantization", if (quant4bit) "\"bnb_4bit\"" else "\"none\"")
        set("n_trials", "$trials")
        return head + rest
    }

    /**
     * The command that runs Heretic in [DIR] (the terminal's working directory). With [unattended] it chooses the best
     * trial, merges and saves to out/<name> by itself; otherwise Heretic asks in the terminal.
     */
    fun runCommand(model: String, configFile: String, unattended: Boolean): String {
        val base = "HERETIC_CONFIG_FILE=${q(configFile)} heretic --model ${q(model)} --checkpoint-action continue"
        return if (!unattended) base
        else "$base --trial-index 0 --model-action save --export-strategy merge --save-directory ${q("out/" + outName(model))}"
    }

    /** Reports Python, PyTorch, GPU and whether Heretic is installed, as one JSON line after HERETIC_CHECK. */
    val checkScript = """
        import json, shutil, sys
        r = {"python": sys.version.split()[0], "torch": None, "cuda": False, "gpu": "", "vram_gb": 0, "mps": False}
        try:
            import torch
            r["torch"] = torch.__version__
            r["cuda"] = torch.cuda.is_available()
            if r["cuda"]:
                r["gpu"] = torch.cuda.get_device_name(0)
                r["vram_gb"] = round(torch.cuda.get_device_properties(0).total_memory / 2**30, 1)
            r["mps"] = bool(getattr(torch.backends, "mps", None) and torch.backends.mps.is_available())
        except Exception:
            pass
        r["heretic"] = shutil.which("heretic") is not None
        print("HERETIC_CHECK " + json.dumps(r))
    """.trimIndent()

    /** Turns a saved Heretic model folder into a GGUF with llama.cpp's converter (fetched once into tools/llama.cpp). */
    val exportScript = """
        import os, subprocess, sys
        src, outtype = sys.argv[1], sys.argv[2]
        if not os.path.isfile(os.path.join(src, "config.json")):
            sys.exit("No saved model in " + src + " (it needs config.json and the weights). Did the Heretic run finish and save?")
        lc = os.path.join("tools", "llama.cpp")
        if not os.path.isdir(lc):
            subprocess.check_call(["git", "clone", "--depth", "1", "https://github.com/ggml-org/llama.cpp", lc])
        subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "-r", os.path.join(lc, "requirements", "requirements-convert_hf_to_gguf.txt")])
        out = src.rstrip("/\\") + "-" + outtype.upper() + ".gguf"
        subprocess.check_call([sys.executable, os.path.join(lc, "convert_hf_to_gguf.py"), src, "--outtype", outtype, "--outfile", out])
        print("GGUF_OUT " + os.path.abspath(out))
    """.trimIndent()

    /** GGUF formats the converter writes directly (no compile step needed). */
    val exportTypes = listOf("q8_0" to "Q8_0 (best for phones that fit it)", "bf16" to "BF16 (full quality, 2x bigger)", "f16" to "F16")

    const val RUN_TOOL = "heretic_run"
    const val TO_PHONE_TOOL = "heretic_to_phone"
    val toolNames = setOf(RUN_TOOL, TO_PHONE_TOOL)

    private fun obj(required: List<String>, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "object"); putJsonObject("properties", props); put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

    val tools = listOf(
        ToolSpec(RUN_TOOL, "Modify a Hugging Face language model with Heretic on the user's computer (needs its GPU; 30–90 minutes for small models). " +
            "Goals: ${goals.joinToString { "${it.id} (${it.label.lowercase()})" }}. It runs in a terminal the user can watch; it picks the best result and saves it " +
            "by itself. Check on it with terminal_read. Afterwards, heretic_to_phone puts it on the phone as a local model.",
            obj(listOf("model")) {
                putJsonObject("model") { put("type", "string"); put("description", "Hugging Face model id, e.g. Qwen/Qwen3-4B-Instruct-2507, or a folder on the computer") }
                putJsonObject("goal") { put("type", "string"); put("description", "Goal id (default decensor)") }
                putJsonObject("quantize_4bit") { put("type", "boolean"); put("description", "Load in 4-bit to fit bigger models in less GPU memory (slightly less accurate)") }
                putJsonObject("trials") { put("type", "integer"); put("description", "Optimization trials (default 100; fewer is faster, less thorough)") }
            }),
        ToolSpec(TO_PHONE_TOOL, "Convert a model saved by Heretic on the computer to GGUF (llama.cpp), download it to the phone and add it as a local model provider.",
            obj(listOf("model")) {
                putJsonObject("model") { put("type", "string"); put("description", "The model id that was processed (or the saved folder path)") }
                putJsonObject("format") { put("type", "string"); put("description", "q8_0 (default), bf16 or f16") }
            }),
    )
}

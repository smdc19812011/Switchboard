package app.switchboard.local

import app.switchboard.data.ApiKind
import app.switchboard.data.ChatMessage
import app.switchboard.data.LocalModel
import app.switchboard.data.Provider
import app.switchboard.data.Role
import app.switchboard.net.ChatClient
import app.switchboard.net.RequestOptions
import app.switchboard.net.StreamEvent
import app.switchboard.ui.localStatusLines
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LocalGpuTest {
    /** Lines as llama-server prints them (captured from a real run), plus an Adreno phone's device list. */
    @Test fun parsesDevicesAndPlacement() {
        val devs = LocalEngine.parseDevices("""
            Available devices:
              GPUOpenCL: QUALCOMM Adreno(TM) 650 (3762 MiB, 3762 MiB free)
              Vulkan0: Adreno (TM) 650 (5631 MiB, 5631 MiB free)
        """.trimIndent())
        assertEquals(listOf("GPUOpenCL", "Vulkan0"), devs.map { it.name })
        assertEquals("OpenCL", devs[0].api); assertEquals(3762L, devs[0].freeMiB)
        assertEquals("GPUOpenCL", LocalEngine.pickDevice(devs, "auto")!!.name)   // one GPU: never both APIs at once
        assertEquals("Vulkan0", LocalEngine.pickDevice(devs, "vulkan")!!.name)
        assertEquals("Vulkan0", LocalEngine.pickDevice(devs.drop(1), "auto")!!.name) // Mali/Exynos/Tensor: Vulkan only
        assertNull(LocalEngine.pickDevice(LocalEngine.parseDevices("Available devices:\n  (none)"), "auto"))
        assertEquals(listOf("-dev", "none"), LocalEngine.gpuArgs(null, -1))
        assertEquals(listOf("-dev", "GPUOpenCL", "-ngl", "auto"), LocalEngine.gpuArgs("GPUOpenCL", -1))
        assertEquals(listOf("-dev", "Vulkan0", "-ngl", "20"), LocalEngine.gpuArgs("Vulkan0", 20))
        val log = """
            0.00.416.259 I llama_prepare_model_devices: using device Vulkan0 (llvmpipe (LLVM 20.1.2, 256 bits)) (unknown id) - 15393 MiB free
            0.00.575.121 I load_tensors: offloading 1 repeating layers to GPU
            0.00.575.121 I load_tensors: offloaded 2/3 layers to GPU
        """.trimIndent()
        assertEquals("llvmpipe (LLVM 20.1.2, 256 bits) (Vulkan)" to "2/3", LocalEngine.placementFromLog(log))
        assertEquals(null to null, LocalEngine.placementFromLog("nothing here"))
        // A real model's loading log is hundreds of lines long; the device line near the top must still be found.
        val longLog = log.lines().first() + "\n" + (1..1500).joinToString("\n") { "0.01.000 I llama_model_loader: - kv $it: some.key = value" } + "\n" + log.lines().last()
        assertEquals("llvmpipe (LLVM 20.1.2, 256 bits) (Vulkan)" to "2/3", LocalEngine.placementFromLog(longLog))
        val gb = 1L shl 30
        val split = localStatusLines(LocalEngine.Progress(LocalEngine.Phase.IDLE, "m", 4 * gb, 4 * gb, gpu = "Adreno 650 (OpenCL)", gpuLayers = "28/36"))
        assertTrue(split.second!!, split.second!!.contains("GPU: Adreno 650 (OpenCL) · 28 of 36 layers on the GPU, the rest on the CPU"))
        val all = localStatusLines(LocalEngine.Progress(LocalEngine.Phase.IDLE, "m", 4 * gb, 4 * gb, gpu = "Adreno 650 (OpenCL)", gpuLayers = "36/36"))
        assertTrue(all.second!!.contains("all 36 layers on the GPU"))
    }

    /** A Snapdragon 8 Elite-class phone: NPU (Hexagon HTP) plus the Adreno GPU seen through OpenCL and Vulkan. */
    @Test fun npuGpuAndCpu() {
        val devs = LocalEngine.parseDevices("""
            Available devices:
              HTP0: Hexagon (2048 MiB, 2048 MiB free)
              GPUOpenCL: QUALCOMM Adreno(TM) 830 (7680 MiB, 7680 MiB free)
              Vulkan0: Adreno (TM) 830 (11520 MiB, 11520 MiB free)
        """.trimIndent())
        assertTrue(devs[0].isNpu && devs[0].api == "Hexagon")
        fun pick(p: String, b: String = "auto") = LocalEngine.pickDevices(devs, p, b).map { it.name }
        assertEquals(listOf("HTP0", "GPUOpenCL"), pick("auto"))            // NPU + one GPU (never OpenCL and Vulkan together)
        assertEquals(listOf("HTP0", "Vulkan0"), pick("auto", "vulkan"))
        assertEquals(listOf("GPUOpenCL"), pick("gpu"))
        assertEquals(listOf("HTP0"), pick("npu"))
        assertEquals(emptyList<String>(), pick("cpu"))
        assertEquals(listOf("GPUOpenCL"), LocalEngine.pickDevices(devs.drop(0).filter { !it.isNpu }, "auto", "auto").map { it.name }) // no NPU: GPU only
        // The NPU reports 0 MiB (= no limit), so layers are placed explicitly: all of them, shared evenly with the GPU.
        assertEquals(listOf("-dev", "HTP0,GPUOpenCL", "-ngl", "all", "-fit", "off", "-ts", "1,1"), LocalEngine.gpuArgs("HTP0,GPUOpenCL", -1))
        assertEquals(listOf("-dev", "HTP0", "-ngl", "all", "-fit", "off"), LocalEngine.gpuArgs("HTP0", -1))
        assertEquals(listOf("-dev", "HTP0", "-ngl", "20", "-fit", "off"), LocalEngine.gpuArgs("HTP0", 20))
        // Exactly what the Snapdragon 8 Gen 5 reported (Adreno 840 + Hexagon v81).
        val real = LocalEngine.parseDevices("Available devices:\n  Vulkan0: Adreno (TM) 840 (15195 MiB, 15195 MiB free)\n  GPUOpenCL: QUALCOMM Adreno(TM) 840 (5542 MiB, 4518 MiB free)\n  HTP0: Hexagon (0 MiB, 0 MiB free)")
        assertEquals(listOf("HTP0", "GPUOpenCL"), LocalEngine.pickDevices(real, "auto", "auto").map { it.name })
        val log = """
            I llama_prepare_model_devices: using device HTP0 (Hexagon) - 2048 MiB free
            I llama_prepare_model_devices: using device GPUOpenCL (QUALCOMM Adreno(TM) 830) - 7680 MiB free
            I load_tensors: offloaded 29/29 layers to GPU
        """.trimIndent()
        val placed = LocalEngine.placementFromLog(log)
        assertEquals("Hexagon (Hexagon) + QUALCOMM Adreno(TM) 830 (OpenCL)" to "29/29", placed)
        val gb = 1L shl 30
        val line = localStatusLines(LocalEngine.Progress(LocalEngine.Phase.IDLE, "m", 2 * gb, 2 * gb, gpu = placed.first, gpuLayers = "20/29")).second!!
        assertTrue(line, line.contains("NPU + GPU: Hexagon (Hexagon) + QUALCOMM Adreno(TM) 830 (OpenCL) · 20 of 29 layers on the NPU and GPU, the rest on the CPU"))
        val npuOnly = localStatusLines(LocalEngine.Progress(LocalEngine.Phase.IDLE, "m", 2 * gb, 2 * gb, gpu = "Hexagon (Hexagon)", gpuLayers = "29/29")).second!!
        assertTrue(npuOnly, npuOnly.contains("NPU: Hexagon (Hexagon) · all 29 layers on the NPU"))
    }

    /**
     * With a GPU build of llama-server and a software Vulkan GPU (Mesa lavapipe): set LLAMA_SERVER_GPU and TINY_GGUF.
     * The engine finds the GPU, splits the model onto it, and runs CPU-only when asked.
     */
    @Test fun runsOnGpuAndCpu() = runBlocking {
        val bin = System.getenv("LLAMA_SERVER_GPU")?.let(::File); val model = System.getenv("TINY_GGUF")?.let(::File)
        assumeTrue(bin?.canExecute() == true && model?.exists() == true)
        LocalEngine.binary = bin; LocalEngine.stateDir = File.createTempFile("state", "").apply { delete(); mkdirs() }
        LocalEngine.extraEnv = mapOf("GGML_VK_VISIBLE_DEVICES" to "0") // lavapipe is a CPU-type device, hidden unless listed
        try {
            val devs = LocalEngine.devices()
            assertTrue("$devs", devs.any { it.api == "Vulkan" })
            val p = Provider(name = "Local", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = LocalEngine.baseUrl, apiKey = "k1", model = "tiny",
                local = LocalModel(path = model!!.path, contextSize = 1024))
            val reply = ChatClient().stream(p, listOf(ChatMessage(role = Role.USER, text = "Hi")), RequestOptions(maxTokens = 4)).toList()
            assertTrue(reply.last() is StreamEvent.TurnEnd)
            val gpu = LocalEngine.progress.value!!
            assertTrue("$gpu", gpu.gpu!!.endsWith("(Vulkan)") && gpu.gpuLayers!!.matches(Regex("\\d+/\\d+")))

            val cpu = p.copy(local = p.local!!.copy(processor = "cpu"))
            ChatClient().stream(cpu, listOf(ChatMessage(role = Role.USER, text = "Hi")), RequestOptions(maxTokens = 4)).toList()
            assertNull(LocalEngine.progress.value!!.gpu)

            // Asking for OpenCL where there is none: no GPU device, so it runs on the CPU rather than failing.
            val ocl = p.copy(local = p.local!!.copy(gpuBackend = "opencl"))
            ChatClient().stream(ocl, listOf(ChatMessage(role = Role.USER, text = "Hi")), RequestOptions(maxTokens = 4)).toList()
            assertNull(LocalEngine.progress.value!!.gpu)
        } finally { LocalEngine.stop(); LocalEngine.extraEnv = emptyMap() }
    }
}

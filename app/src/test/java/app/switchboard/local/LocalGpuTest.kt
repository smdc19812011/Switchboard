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
        val gb = 1L shl 30
        val split = localStatusLines(LocalEngine.Progress(LocalEngine.Phase.IDLE, "m", 4 * gb, 4 * gb, gpu = "Adreno 650 (OpenCL)", gpuLayers = "28/36"))
        assertTrue(split.second!!, split.second!!.contains("GPU: Adreno 650 (OpenCL) · 28 of 36 layers on the GPU, the rest on the CPU"))
        val all = localStatusLines(LocalEngine.Progress(LocalEngine.Phase.IDLE, "m", 4 * gb, 4 * gb, gpu = "Adreno 650 (OpenCL)", gpuLayers = "36/36"))
        assertTrue(all.second!!.contains("all 36 layers on the GPU"))
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

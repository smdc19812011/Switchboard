package app.switchboard

import android.app.Application
import app.switchboard.local.LocalEngine
import app.switchboard.local.ModelDownloads
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The bundled llama-server (arm64 only) runs local GGUF models; scheduled tasks use it too, so set it up for the whole process.
        LocalEngine.binary = File(applicationInfo.nativeLibraryDir, "libllama-server.so").takeIf { it.exists() }
        LocalEngine.stateDir = filesDir
        // Model downloads keep a foreground service (progress notification, wake and Wi-Fi locks) while they run.
        val ctx = this
        ModelDownloads.hooks = object : ModelDownloads.Hooks {
            override fun progress(key: String, text: String) = WorkService.hold(ctx, key, text)
            override fun end(key: String, result: String?) { WorkService.release(ctx, key); result?.let { WorkService.announce(ctx, it) } }
        }
    }
}

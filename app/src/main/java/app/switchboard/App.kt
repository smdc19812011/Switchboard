package app.switchboard

import android.app.Application
import app.switchboard.local.LocalEngine
import app.switchboard.local.ModelDownloads
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The ":engine" process only runs EngineService (the llama.cpp engine); it needs none of the app setup.
        if ((if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else "").endsWith(":engine")) return
        CrashReport.install(this)
        // The bundled llama-server (arm64 only) runs local GGUF models; scheduled tasks use it too, so set it up for the whole process.
        LocalEngine.binary = File(applicationInfo.nativeLibraryDir, "libllama-server.so").takeIf { it.exists() }
        LocalEngine.stateDir = filesDir
        LocalEngine.launcher = ServiceLauncher(this)
        LocalEngine.isSamsung = android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        // Model downloads keep a foreground service (progress notification, wake and Wi-Fi locks) while they run.
        val ctx = this
        ModelDownloads.hooks = object : ModelDownloads.Hooks {
            override fun progress(key: String, text: String) = WorkService.hold(ctx, key, text)
            override fun end(key: String, result: String?) { WorkService.release(ctx, key); result?.let { WorkService.announce(ctx, it) } }
        }
    }
}

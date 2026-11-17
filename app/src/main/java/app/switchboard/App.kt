package app.switchboard

import android.app.Application
import app.switchboard.local.LocalEngine
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The bundled llama-server (arm64 only) runs local GGUF models; scheduled tasks use it too, so set it up for the whole process.
        LocalEngine.binary = File(applicationInfo.nativeLibraryDir, "libllama-server.so").takeIf { it.exists() }
        LocalEngine.stateDir = filesDir
    }
}

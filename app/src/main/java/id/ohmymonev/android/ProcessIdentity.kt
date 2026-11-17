package id.ohmymonev.android

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileInputStream

internal object ProcessIdentity {
    fun isDiagnostics(name: String?): Boolean = name?.endsWith(":diagnostics") == true

    fun isMain(name: String?, packageName: String, allowUnknown: Boolean = false): Boolean =
        !isDiagnostics(name) && (name == packageName || (name == null && allowUnknown))

    fun lookup(
        sdk: Int,
        processName: () -> String?,
        procCmdline: () -> String?,
        processForPid: () -> String?,
    ): String? {
        fun read(block: () -> String?): String? = try {
            block()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

        if (sdk >= Build.VERSION_CODES.P) return read(processName)
        return read(procCmdline) ?: read(processForPid)
    }

    fun current(context: Context): String? = lookup(
        Build.VERSION.SDK_INT,
        processName = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() else null },
        procCmdline = {
            FileInputStream(File("/proc/self/cmdline")).use { input ->
                val bytes = ByteArray(256)
                var size = 0
                while (size < bytes.size) {
                    val next = input.read()
                    if (next <= 0) break
                    bytes[size++] = next.toByte()
                }
                if (size == 0) null else String(bytes, 0, size, Charsets.UTF_8)
            }
        },
        processForPid = {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@lookup null
            manager.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        },
    )
}

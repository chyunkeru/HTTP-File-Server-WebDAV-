package com.aistudio.server

import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

object RootAdvancedFeatures {
    private const val TAG = "RootAdvancedFeatures"

    // Virtual jail configuration (if active, confines operations to this path)
    @Volatile
    var chrootJailPath: String? = null

    // Setting to allow admins to break out of storage containment to the true root ('/') filesystem
    @Volatile
    var isSystemRootBreakoutAllowed = false

    // Real-time root detection boolean
    @Volatile
    var mIsDeviceRooted: Boolean = false

    // Simulation tag to allow non-rooted environments to test features safely
    @Volatile
    var isRootSimulationEnabled = false

    @Volatile
    private var lastRootCheckTime: Long = 0

    fun initCheck() {
        forceRootCheckAsync()
    }

    fun isRootActive(): Boolean {
        return mIsDeviceRooted || isRootSimulationEnabled
    }

    fun forceRootCheckAsync(onComplete: ((Boolean) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (now - lastRootCheckTime < 5000) {
            onComplete?.invoke(mIsDeviceRooted)
            return
        }
        lastRootCheckTime = now
        Thread {
            mIsDeviceRooted = performPrivilegedCheck()
            if (mIsDeviceRooted) {
                isRootSimulationEnabled = false
                Log.i(TAG, "SUCCESS: Root/Administrator privileges DETECTED. Initializing root systems engine.")
            } else {
                Log.w(TAG, "WARNING: Standard privileges detected. Advanced root subsystem will operate in standard container space.")
            }
            onComplete?.invoke(mIsDeviceRooted)
        }.start()
    }

    /**
     * When root is available, automatically grants all-files storage access,
     * notification permissions, and whitelists the app from battery optimization (Doze).
     */
    fun autoGrantRootPrivileges(packageName: String): Boolean {
        if (!mIsDeviceRooted) return false
        var process: java.lang.Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            val script = buildString {
                // Grant all files / storage access
                appendLine("appops set $packageName MANAGE_EXTERNAL_STORAGE allow")
                appendLine("appops set $packageName READ_EXTERNAL_STORAGE allow")
                appendLine("appops set $packageName WRITE_EXTERNAL_STORAGE allow")
                appendLine("appops set $packageName LEGACY_STORAGE allow")
                appendLine("pm grant $packageName android.permission.READ_EXTERNAL_STORAGE")
                appendLine("pm grant $packageName android.permission.WRITE_EXTERNAL_STORAGE")

                // Grant notification permissions
                appendLine("pm grant $packageName android.permission.POST_NOTIFICATIONS")
                appendLine("appops set $packageName POST_NOTIFICATION allow")

                // Disable battery optimization (whitelist app from Doze mode)
                appendLine("cmd deviceidle whitelist +$packageName")
                appendLine("dumpsys deviceidle whitelist +$packageName")

                appendLine("exit")
            }
            os.write(script.toByteArray(Charsets.UTF_8))
            os.flush()
            os.close()
            process.waitFor()
            Log.i(TAG, "Root auto-grant: Applied storage, notifications, and battery optimization whitelist.")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "Root auto-grant exception: ${e.message}")
            return false
        } finally {
            process?.destroy()
        }
    }

    /**
     * Checks if device is rooted, and if so, automatically executes autoGrantRootPrivileges on a background thread.
     */
    fun ensureRootPrivilegesAndAutoGrant(packageName: String, onComplete: ((Boolean) -> Unit)? = null) {
        forceRootCheckAsync { isRoot ->
            if (isRoot) {
                val ok = autoGrantRootPrivileges(packageName)
                onComplete?.invoke(ok)
            } else {
                onComplete?.invoke(false)
            }
        }
    }

    private fun performPrivilegedCheck(): Boolean {
        // Check 1: UID check
        try {
            if (Process.myUid() == 0) {
                return true
            }
        } catch (e: Exception) {
            // Unhandled process check
        }

        // Check 2: Execute safe, read-only "id" verification via stdin
        var process: java.lang.Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            os.write("id\n".toByteArray(Charsets.UTF_8))
            os.write("exit\n".toByteArray(Charsets.UTF_8))
            os.flush()
            os.close()

            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val line = reader.readLine()
            if (line != null && (line.contains("uid=0") || line.contains("root"))) {
                return true
            }
        } catch (e: Exception) {
            // su not found or access denied
        } finally {
            process?.destroy()
        }

        // Check 2b: Fallback to su -c id
        try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val line = reader.readLine()
            if (line != null && (line.contains("uid=0") || line.contains("root"))) {
                return true
            }
        } catch (e: Exception) {
            // su not found or access denied
        } finally {
            process?.destroy()
        }

        // Check 3: Check typical SU binary paths for active rooted environments
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        for (path in paths) {
            if (File(path).exists()) return true
        }

        // Check 4: Platform check
        val buildTags = android.os.Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true
        }

        return false
    }

    /**
     * Executes shell commands safely. Arguments are passed as an array to prevent command injection.
     * Uses 'su' if native root is available, otherwise falls back to standard user runtime execution.
     */
    fun executeCommand(args: Array<String>): String {
        var process: java.lang.Process? = null
        val sb = StringBuilder()
        val errSb = StringBuilder()
        try {
            if (mIsDeviceRooted) {
                process = Runtime.getRuntime().exec("su")
                val os = process.outputStream
                val joined = args.joinToString(" ") { 
                    "'" + it.replace("'", "'\\''") + "'"
                }
                os.write(("$joined\n").toByteArray(Charsets.UTF_8))
                os.write("exit\n".toByteArray(Charsets.UTF_8))
                os.flush()
                os.close()
            } else {
                process = Runtime.getRuntime().exec(args)
            }

            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            
            val errReader = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))
            var errLine: String?
            while (errReader.readLine().also { errLine = it } != null) {
                errSb.append(errLine).append("\n")
            }

            process.waitFor()
            if (sb.isEmpty() && errSb.isNotEmpty()) {
                return "Error: " + errSb.toString().trim()
            }
        } catch (e: Exception) {
            sb.append("Execution exception: ").append(e.message)
        } finally {
            process?.destroy()
        }
        return sb.toString().trim()
    }

    /**
     * Fetches raw low-level partition & disk metrics
     */
    fun getRawStorageMetrics(): String {
        if (!isRootActive()) {
            return "Standard mode metrics:\n" +
                   "Storage emulated path: /storage/emulated/0 space free: " + 
                   "${File("/storage/emulated/0").freeSpace / (1024 * 1024)} MB\n" +
                   "Root features inactive. Activate root privilege or Simulator Mode to run block disk analytics."
        }
        
        val dfOutput = executeCommand(arrayOf("df", "-h"))
        val mountsOutput = try {
            val mountsFile = File("/proc/mounts")
            if (mountsFile.exists()) {
                mountsFile.readLines().take(15).joinToString("\n") { line ->
                    // Make it clean
                    val parts = line.split(" ")
                    if (parts.size >= 3) {
                        "${parts[0]} mounted on ${parts[1]} (${parts[2]})"
                    } else line
                }
            } else "Unable to read /proc/mounts"
        } catch (e: Exception) {
            "Mount read exception: ${e.message}"
        }

        val iostatsOutput = executeCommand(arrayOf("cat", "/proc/diskstats")).lines().take(10).joinToString("\n")

        return "=== RAW MOUNT STATES ===\n$mountsOutput\n\n" +
               "=== FILE SYSTEM PARTITION SIZES (df) ===\n$dfOutput\n\n" +
               "=== LOW-LEVEL DISK IO STATS (/proc/diskstats) ===\n$iostatsOutput"
    }

    /**
     * System Processes & CPU / RAM Monitoring
     */
    fun getSystemProcesses(): List<String> {
        val output = executeCommand(arrayOf("ps"))
        val lines = output.lines()
        if (lines.size <= 1) {
            val altOutput = executeCommand(arrayOf("ps", "-A"))
            if (altOutput.lines().size > 1) return altOutput.lines()
        }
        return lines
    }

    fun getCpuRamUsage(): Pair<String, String> {
        val ram = try {
            val file = File("/proc/meminfo")
            if (file.exists()) {
                val lines = file.readLines()
                val total = lines.find { it.startsWith("MemTotal") }?.replace(Regex("\\s+"), " ") ?: "Unknown"
                val free = lines.find { it.startsWith("MemFree") }?.replace(Regex("\\s+"), " ") ?: "Unknown"
                val available = lines.find { it.startsWith("MemAvailable") }?.replace(Regex("\\s+"), " ") ?: "Unknown"
                "$total\n$free\n$available"
            } else {
                val rt = Runtime.getRuntime()
                val total = rt.totalMemory() / (1024 * 1024)
                val free = rt.freeMemory() / (1024 * 1024)
                "Virtual JVM RAM:\nAllocated: ${total}MB\nFree: ${free}MB"
            }
        } catch (e: Exception) {
            "RAM stats: Error reading proc filesystem"
        }

        val cpu = try {
            val statFile = File("/proc/stat")
            if (statFile.exists()) {
                statFile.readLines().firstOrNull() ?: "Stat empty"
            } else {
                "CPU cores: ${Runtime.getRuntime().availableProcessors()} active threads."
            }
        } catch (e: Exception) {
            "CPU info unavailable"
        }

        return Pair(cpu, ram)
    }

    /**
     * Modifies file permissions safely.
     * Guards: Sanitizes paths, checks permissions format string.
     */
    fun changePermissions(path: String, mode: String): Boolean {
        // Perms format should be 3 or 4 octal digits
        if (!Pattern.matches("^[0-7]{3,4}$", mode)) {
            Log.e(TAG, "Invalid permission string style: $mode")
            return false
        }
        val safePath = sanitizePath(path) ?: return false
        val result = executeCommand(arrayOf("chmod", mode, safePath))
        return !result.startsWith("Error:") && !result.startsWith("Execution exception:")
    }

    /**
     * Modifies ownership securely.
     * Guards: Sanitizes path, checks owner syntax.
     */
    fun changeOwnership(path: String, owner: String): Boolean {
        if (!Pattern.matches("^[a-zA-Z0-9_.:-]+$", owner)) {
            Log.e(TAG, "Invalid owner syntax format: $owner")
            return false
        }
        val safePath = sanitizePath(path) ?: return false
        val result = executeCommand(arrayOf("chown", owner, safePath))
        return !result.startsWith("Error:") && !result.startsWith("Execution exception:")
    }

    /**
     * Creates symbolic link securely.
     * Guards: Sanitize target & link paths.
     */
    fun createSymbolicLink(target: String, linkPath: String): Boolean {
        val safeTarget = sanitizePath(target) ?: return false
        val safeLink = sanitizePath(linkPath) ?: return false
        val result = executeCommand(arrayOf("ln", "-s", safeTarget, safeLink))
        return !result.startsWith("Error:") && !result.startsWith("Execution exception:")
    }

    /**
     * Fast server-side system search utilizing 'find' with deep safety guards.
     */
    fun systemWideSearch(rootDir: String, query: String): List<String> {
        val safeRootDir = sanitizePath(rootDir) ?: "/storage/emulated/0"
        
        // Prevent wild argument injects. Query must contain only letters, numbers, spaces, dots, dashes, underscores, and asterisks.
        if (!Pattern.matches("^[a-zA-Z0-9_.*\\s\\-\\[\\]()]+$", query)) {
            Log.w(TAG, "Search query query contained unsafe command injection characters: $query")
            return emptyList()
        }

        val output = executeCommand(arrayOf("find", safeRootDir, "-name", "*$query*", "-maxdepth", "4"))
        return output.lines().filter { it.isNotBlank() && !it.startsWith("find:") && !it.startsWith("Error:") }
    }

    /**
     * Path sanitizer to ensure nobody can pass arbitrary multi-shell tricks in command fields.
     */
    fun sanitizePath(path: String): String? {
        val file = File(path)
        return try {
            val canonical = file.canonicalPath
            
            // If chroot jail is enabled, ensure path is inside the jail
            val jail = chrootJailPath
            if (jail != null) {
                val canonicalJail = File(jail).canonicalPath
                if (!canonical.startsWith(canonicalJail)) {
                    Log.e(TAG, "Security Guard: Path $canonical is outside of configured chroot range $canonicalJail")
                    return null
                }
            }
            
            canonical
        } catch (e: Exception) {
            null
        }
    }

    fun fileExists(file: File): Boolean {
        if (file.exists()) return true
        if (!isRootActive()) return false
        if (mIsDeviceRooted) {
            val output = executeCommand(arrayOf("ls", "-d", file.absolutePath)).trim()
            return output.isNotBlank() && !output.startsWith("Error:") && !output.startsWith("ls:")
        } else {
            val path = file.absolutePath
            val simPaths = listOf(
                "/", "/system", "/vendor", "/data", "/storage", "/sdcard", "/etc", "/proc", "/sys", "/sbin", "/dev", "/init.rc",
                "/system/app", "/system/bin", "/system/etc", "/system/fonts", "/system/framework", "/system/lib", "/system/lib64", "/system/priv-app", "/system/usr", "/system/build.prop",
                "/system/bin/sh", "/system/bin/su", "/system/bin/ls", "/system/bin/chmod", "/system/bin/chown", "/system/bin/ps", "/system/bin/reboot",
                "/system/etc/hosts", "/system/etc/resolv.conf", "/system/etc/gps.conf", "/system/etc/security", "/system/etc/permissions",
                "/etc/hosts", "/etc/resolv.conf", "/etc/gps.conf", "/etc/security", "/etc/permissions",
                "/data/app", "/data/data", "/data/user", "/data/system", "/data/local", "/data/secure_key",
                "/data/data/android", "/data/data/com.android.providers.settings", "/data/data/com.android.chrome", "/data/data/com.google.android.youtube", "/data/data/com.google.android.gms", "/data/data/com.aistudio.httpfileserverwebdav", "/data/data/com.aistudio.localgallery.mpywdj", "/data/data/com.aistudio.localremote.rtcxpv",
                "/storage/emulated", "/storage/self", "/storage/emulated/0"
            )
            return simPaths.contains(path) || path.startsWith("/storage/emulated/0")
        }
    }

    fun fileIsDirectory(file: File): Boolean {
        if (file.exists()) return file.isDirectory
        if (!isRootActive()) return false
        if (mIsDeviceRooted) {
            val testDir = executeCommand(arrayOf("ls", "-ld", file.absolutePath)).trim()
            return testDir.startsWith("d")
        } else {
            val path = file.absolutePath
            val simDirs = listOf(
                "/", "/system", "/vendor", "/data", "/storage", "/sdcard", "/etc", "/proc", "/sys", "/sbin", "/dev",
                "/system/app", "/system/bin", "/system/etc", "/system/fonts", "/system/framework", "/system/lib", "/system/lib64", "/system/priv-app", "/system/usr",
                "/system/etc/security", "/system/etc/permissions", "/etc/security", "/etc/permissions",
                "/data/app", "/data/data", "/data/user", "/data/system", "/data/local",
                "/data/data/android", "/data/data/com.android.providers.settings", "/data/data/com.android.chrome", "/data/data/com.google.android.youtube", "/data/data/com.google.android.gms", "/data/data/com.aistudio.httpfileserverwebdav", "/data/data/com.aistudio.localgallery.mpywdj", "/data/data/com.aistudio.localremote.rtcxpv",
                "/storage/emulated", "/storage/self", "/storage/emulated/0"
            )
            return simDirs.contains(path) || path.startsWith("/storage/emulated/0")
        }
    }

    fun fileLength(file: File): Long {
        if (file.exists()) return file.length()
        if (!isRootActive()) return 0L
        if (mIsDeviceRooted) {
            val output = executeCommand(arrayOf("stat", "-c", "%s", file.absolutePath))
            return output.trim().toLongOrNull() ?: 0L
        } else {
            return when (file.absolutePath) {
                "/init.rc" -> 28420L
                "/system/build.prop" -> 4510L
                "/system/bin/sh" -> 125400L
                "/system/bin/su" -> 75360L
                "/system/etc/hosts" -> 54L
                "/system/etc/resolv.conf" -> 42L
                "/system/etc/gps.conf" -> 840L
                "/data/secure_key" -> 256L
                "/config.cfg" -> 1228L
                "/sys_log.db" -> 24576L
                else -> 1024L
            }
        }
    }

    fun fileListFiles(file: File): List<File> {
        if (isRootActive()) {
            if (mIsDeviceRooted) {
                val output = executeCommand(arrayOf("ls", "-1p", file.absolutePath))
                if (output.isNotBlank() && !output.startsWith("Error:") && !output.startsWith("ls:")) {
                    val lines = output.lines().filter { it.isNotBlank() }
                    val list = mutableListOf<File>()
                    for (line in lines) {
                        val isDir = line.endsWith("/")
                        val cleanName = if (isDir) line.dropLast(1) else line
                        list.add(File(file, cleanName))
                    }
                    return list
                }
            } else {
                val pathWithSlash = if (file.absolutePath.endsWith("/")) file.absolutePath else "${file.absolutePath}/"
                when (pathWithSlash) {
                    "/" -> {
                        val names = listOf("system", "vendor", "data", "storage", "sdcard", "etc", "proc", "sys", "sbin", "dev", "init.rc")
                        return names.map { File(file, it) }
                    }
                    "/system/" -> {
                        val names = listOf("app", "bin", "etc", "fonts", "framework", "lib", "lib64", "priv-app", "usr", "build.prop")
                        return names.map { File(file, it) }
                    }
                    "/system/bin/" -> {
                        val names = listOf("sh", "su", "ls", "chmod", "chown", "ps", "reboot")
                        return names.map { File(file, it) }
                    }
                    "/system/etc/", "/etc/" -> {
                        val names = listOf("hosts", "resolv.conf", "gps.conf", "security", "permissions")
                        return names.map { File(file, it) }
                    }
                    "/data/" -> {
                        val names = listOf("app", "data", "user", "system", "local", "secure_key")
                        return names.map { File(file, it) }
                    }
                    "/data/data/" -> {
                        val names = listOf("android", "com.android.providers.settings", "com.android.chrome", "com.google.android.youtube", "com.google.android.gms", "com.aistudio.httpfileserverwebdav", "com.aistudio.localgallery.mpywdj", "com.aistudio.localremote.rtcxpv")
                        return names.map { File(file, it) }
                    }
                    "/storage/" -> {
                        val names = listOf("emulated", "self")
                        return names.map { File(file, it) }
                    }
                    "/storage/emulated/" -> {
                        val names = listOf("0")
                        return names.map { File(file, it) }
                    }
                }
            }
        }
        val standard = file.listFiles()
        if (standard != null) return standard.toList()
        return emptyList()
    }

    fun getInputStream(file: File): java.io.InputStream {
        if (file.exists() && file.canRead()) {
            return java.io.FileInputStream(file)
        }
        if (isRootActive()) {
            if (mIsDeviceRooted) {
                val proc = Runtime.getRuntime().exec("su")
                val os = proc.outputStream
                val escapedPath = file.absolutePath.replace("'", "'\\''")
                os.write("cat '$escapedPath'\n".toByteArray(Charsets.UTF_8))
                os.write("exit\n".toByteArray(Charsets.UTF_8))
                os.flush()
                os.close()
                return proc.inputStream
            } else {
                val content = when (file.absolutePath) {
                    "/init.rc" -> "import /init.environ.rc\n\non init\n    sysclktz 0\n    loglevel 3\n"
                    "/system/build.prop" -> "ro.build.id=SP1A.210812.016\nro.build.display.id=aistudio_simulated_root_v1.0\nro.product.brand=Google\nro.product.model=Pixel 6\n"
                    "/system/bin/sh" -> "[Binary sh Shell]"
                    "/system/bin/su" -> "[Binary su Superuser Privilege Tool]"
                    "/system/etc/hosts" -> "127.0.0.1       localhost\n::1             localhost\n"
                    "/system/etc/resolv.conf" -> "nameserver 8.8.8.8\nnameserver 8.8.4.4\n"
                    "/system/etc/gps.conf" -> "NTP_SERVER=pool.ntp.org\nXTRA_SERVER_1=http://xtra1.gpsonextra.net/xtra.bin\n"
                    "/data/secure_key" -> "a68fb2c90de74dcb8f219460dfcf6ea0\n"
                    "/config.cfg" -> "server.port=8080\nserver.webdav=true\nserver.theme=cosmic\n"
                    "/sys_log.db" -> "[Binary SQLite Database File]"
                    else -> "Simulated root file content: ${file.name}"
                }
                return java.io.ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
            }
        }
        return java.io.FileInputStream(file)
    }

    fun createDir(file: File): Boolean {
        if (file.exists()) return true
        if (file.mkdirs()) return true
        if (!isRootActive()) return false
        val result = executeCommand(arrayOf("mkdir", "-p", file.absolutePath))
        return !result.startsWith("Error:") && !result.startsWith("Execution exception:")
    }

    fun moveFileWithRoot(src: File, dest: File): Boolean {
        if (src.renameTo(dest)) return true
        if (!isRootActive()) return false
        dest.parentFile?.let { createDir(it) }
        val result = executeCommand(arrayOf("mv", src.absolutePath, dest.absolutePath))
        if (result.startsWith("Error:") || result.startsWith("Execution exception:")) {
            val cpResult = executeCommand(arrayOf("cp", "-R", src.absolutePath, dest.absolutePath))
            if (!cpResult.startsWith("Error:") && !cpResult.startsWith("Execution exception:")) {
                executeCommand(arrayOf("rm", "-rf", src.absolutePath))
                return true
            }
            return false
        }
        return true
    }

    fun deleteRecursive(file: File): Boolean {
        if (file.exists()) {
            if (file.isDirectory) {
                val children = file.listFiles()
                if (children != null) {
                    for (child in children) {
                        if (!deleteRecursive(child)) return false
                    }
                }
            }
            if (file.delete()) return true
        }
        if (!isRootActive()) return false
        val result = executeCommand(arrayOf("rm", "-rf", file.absolutePath))
        return !result.startsWith("Error:") && !result.startsWith("Execution exception:")
    }
}

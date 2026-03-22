package com.xiaomo.termux

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Manages the Termux bootstrap lifecycle.
 *
 * Bootstrap zip is embedded in libtermux-bootstrap.so via NDK (same approach as official Termux).
 * At runtime, the zip is extracted to a staging directory, symlinks are created from SYMLINKS.txt,
 * then the staging dir is renamed to the final PREFIX.
 */
class BootstrapManager(private val context: Context) {

    companion object {
        private const val TAG = "BootstrapManager"

        /**
         * Load the bootstrap zip bytes from the native library (libtermux-bootstrap.so).
         * The zip is embedded at build time via assembly .incbin directive.
         */
        @JvmStatic
        fun loadZipBytes(): ByteArray {
            System.loadLibrary("termux-bootstrap")
            return getZip()
        }

        @JvmStatic
        private external fun getZip(): ByteArray
    }

    private val env = TermuxEnvironment(context)

    /**
     * Returns true if the bootstrap runtime is ready to use.
     */
    fun isReady(): Boolean = env.isReady()

    /**
     * Ensure the bootstrap is extracted and ready.
     * Uses the official Termux extraction approach: staging dir + rename.
     * No-op if already ready.
     */
    suspend fun ensureReady(
        onProgress: (BootstrapProgress) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isReady()) {
                onProgress(BootstrapProgress(BootstrapProgress.State.READY, message = "Runtime already installed"))
                return@withContext Result.success(Unit)
            }

            onProgress(BootstrapProgress(BootstrapProgress.State.EXTRACTING, message = "Loading bootstrap..."))

            val prefixDir = env.prefixDir
            val stagingDir = env.stagingPrefixDir

            // Clean up any leftover staging or prefix dir
            stagingDir.deleteRecursively()
            prefixDir.deleteRecursively()

            // Create staging directory
            stagingDir.mkdirs()

            // Load zip bytes from native library
            val zipBytes = loadZipBytes()
            Log.d(TAG, "Bootstrap zip loaded, size: ${zipBytes.size} bytes")

            onProgress(BootstrapProgress(BootstrapProgress.State.EXTRACTING, message = "Extracting bootstrap..."))

            // Extract zip to staging dir (same logic as TermuxInstaller.java)
            val buffer = ByteArray(8096)
            val symlinks = mutableListOf<Pair<String, String>>()
            var entryCount = 0

            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipInput ->
                var zipEntry = zipInput.nextEntry
                while (zipEntry != null) {
                    if (zipEntry.name == "SYMLINKS.txt") {
                        // Parse symlinks file
                        val reader = BufferedReader(InputStreamReader(zipInput))
                        var line = reader.readLine()
                        while (line != null) {
                            val parts = line.split("\u2190") // left arrow character ←
                            if (parts.size != 2) {
                                throw RuntimeException("Malformed symlink line: $line")
                            }
                            val oldPath = parts[0]
                            val newPath = stagingDir.absolutePath + "/" + parts[1]
                            symlinks.add(oldPath to newPath)

                            // Ensure parent directory exists
                            File(newPath).parentFile?.mkdirs()

                            line = reader.readLine()
                        }
                    } else {
                        val targetFile = File(stagingDir, zipEntry.name)
                        val isDirectory = zipEntry.isDirectory

                        if (isDirectory) {
                            targetFile.mkdirs()
                        } else {
                            targetFile.parentFile?.mkdirs()
                            FileOutputStream(targetFile).use { outStream ->
                                var readBytes: Int
                                while (zipInput.read(buffer).also { readBytes = it } != -1) {
                                    outStream.write(buffer, 0, readBytes)
                                }
                            }

                            // Set execute permissions for binaries and libraries
                            val name = zipEntry.name
                            if (name.startsWith("bin/") || name.startsWith("libexec") ||
                                name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")
                            ) {
                                Os.chmod(targetFile.absolutePath, 448) // 0700
                            }
                        }

                        entryCount++
                        if (entryCount % 100 == 0) {
                            onProgress(
                                BootstrapProgress(
                                    BootstrapProgress.State.EXTRACTING,
                                    current = entryCount,
                                    message = "Extracting files... ($entryCount)"
                                )
                            )
                        }
                    }
                    zipEntry = zipInput.nextEntry
                }
            }

            if (symlinks.isEmpty()) {
                throw RuntimeException("No SYMLINKS.txt encountered in bootstrap zip")
            }

            // Create symlinks
            onProgress(
                BootstrapProgress(
                    BootstrapProgress.State.CONFIGURING,
                    message = "Creating ${symlinks.size} symlinks..."
                )
            )
            for ((target, linkPath) in symlinks) {
                Os.symlink(target, linkPath)
            }
            Log.d(TAG, "Created ${symlinks.size} symlinks, extracted $entryCount files")

            // Rename staging to final prefix (atomic on same filesystem)
            if (!stagingDir.renameTo(prefixDir)) {
                throw RuntimeException("Moving staging prefix to prefix directory failed")
            }

            // Create home and tmp directories
            onProgress(BootstrapProgress(BootstrapProgress.State.CONFIGURING, message = "Configuring environment..."))
            env.ensureDirectories()

            // Patch hardcoded Termux paths in text/script files
            onProgress(BootstrapProgress(BootstrapProgress.State.CONFIGURING, message = "Patching paths..."))
            patchTermuxPaths(prefixDir)

            generateExecWrappers()

            // Verify
            if (!isReady()) {
                return@withContext Result.failure(RuntimeException("Bootstrap extraction completed but shell not found"))
            }

            onProgress(BootstrapProgress(BootstrapProgress.State.READY, message = "Runtime ready"))
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap setup failed", e)
            // Clean up on failure
            env.stagingPrefixDir.deleteRecursively()
            onProgress(
                BootstrapProgress(
                    BootstrapProgress.State.ERROR,
                    message = "Setup failed: ${e.message}"
                )
            )
            Result.failure(e)
        }
    }

    /** Public entry point for regenerating wrappers on existing installs. */
    fun regenerateWrappers() = generateExecWrappers()

    // ---- Path patching -------------------------------------------------------

    /** Old Termux prefix baked into the official bootstrap binaries & scripts. */
    private val TERMUX_OLD_PREFIX = "/data/data/com.termux/files/usr"
    private val TERMUX_OLD_HOME = "/data/data/com.termux/files/home"
    private val TERMUX_OLD_BASE = "/data/data/com.termux"

    /**
     * Replace hard-coded `/data/data/com.termux` paths in **text** files under
     * [prefixDir].  Binary (ELF) files are skipped – they are handled at runtime
     * via wrapper scripts in exec-wrappers.sh that pass explicit path arguments.
     */
    private fun patchTermuxPaths(prefixDir: File) {
        val ourBase = context.applicationInfo.dataDir   // e.g. /data/data/com.xiaomo.androidforclaw
        val ourPrefix = "${ourBase}/files/usr"
        val ourHome = "${ourBase}/files/home"

        if (TERMUX_OLD_BASE == ourBase) {
            Log.d(TAG, "Package path matches com.termux, no patching needed")
            return
        }

        var patchedFiles = 0
        val textExtensions = setOf(
            "sh", "bash", "py", "pl", "rb", "conf", "cfg", "list", "sources",
            "bashrc", "profile", "inputrc", "nanorc", "xattr.conf"
        )

        prefixDir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            if (isElfBinary(file)) return@forEach

            val name = file.name
            val ext = name.substringAfterLast('.', "")
            // Patch known text files: by extension, or files in etc/, or known names
            val shouldPatch = ext in textExtensions
                    || file.absolutePath.contains("/etc/")
                    || file.absolutePath.contains("/share/")
                    || name in setOf("bashrc", "profile", "motd", "motd.sh", "motd-playstore",
                "termux-login.sh", "resolv.conf", "hosts")

            if (!shouldPatch) return@forEach

            try {
                val content = file.readText()
                if (content.contains(TERMUX_OLD_BASE)) {
                    val patched = content
                        .replace(TERMUX_OLD_PREFIX, ourPrefix)
                        .replace(TERMUX_OLD_HOME, ourHome)
                        .replace(TERMUX_OLD_BASE, ourBase)
                    file.writeText(patched)
                    patchedFiles++
                }
            } catch (_: Exception) {
                // Skip files that can't be read as text
            }
        }
        Log.d(TAG, "Patched $patchedFiles text files (replaced com.termux → ${context.packageName})")
    }

    /** Quick ELF check: first 4 bytes = 0x7f 'E' 'L' 'F'. */
    private fun isElfBinary(file: File): Boolean {
        if (file.length() < 4) return false
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(4)
                stream.read(header)
                header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte()
                        && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) { false }
    }

    // ---- Exec wrappers -------------------------------------------------------

    /**
     * Generate exec-wrappers.sh: shell functions that invoke each Termux binary via
     * /system/bin/linker64, bypassing the SELinux W^X restriction on Android 10+.
     *
     * Also generates wrapper functions for dpkg/apt that pass the correct --admindir
     * and config paths, since those binaries have /data/data/com.termux hard-coded.
     */
    private fun generateExecWrappers() {
        val binDir = File(env.prefixDir, "bin")
        val wrappersFile = env.execWrappersFile
        wrappersFile.parentFile?.mkdirs()

        val prefix = env.prefixDir.absolutePath
        val dataDir = context.applicationInfo.dataDir
        val sb = StringBuilder()
        sb.appendLine("# Auto-generated by BootstrapManager -- do not edit")
        sb.appendLine("# Wraps Termux binaries via linker64 to bypass SELinux W^X on Android 10+")
        sb.appendLine()

        // Export key env vars so child processes find the right paths
        sb.appendLine("export PREFIX=\"$prefix\"")
        sb.appendLine("export HOME=\"${dataDir}/files/home\"")
        sb.appendLine("export TMPDIR=\"$prefix/tmp\"")
        sb.appendLine("export LD_LIBRARY_PATH=\"$prefix/lib\"")
        sb.appendLine("export TERMUX_PREFIX=\"$prefix\"")
        sb.appendLine("export TERMUX_HOME=\"${dataDir}/files/home\"")
        sb.appendLine()

        // Special wrappers for tools with hard-coded /data/data/com.termux paths
        val dpkgAdminDir = "$prefix/var/lib/dpkg"
        val dpkgConfDir = "$prefix/etc/dpkg"
        val aptConfDir = "$prefix/etc/apt"

        // dpkg wrapper: override admindir + instdir via env vars
        sb.appendLine("dpkg() {")
        sb.appendLine("  DPKG_ADMINDIR=\"$dpkgAdminDir\" \\")
        sb.appendLine("  /system/bin/linker64 $prefix/bin/dpkg \\")
        sb.appendLine("    --admindir=\"$dpkgAdminDir\" \\")
        sb.appendLine("    --instdir=\"$prefix\" \\")
        sb.appendLine("    \"\$@\"")
        sb.appendLine("}")
        sb.appendLine()

        // apt wrapper: set APT_CONFIG and pass -o for correct paths
        sb.appendLine("apt() {")
        sb.appendLine("  /system/bin/linker64 $prefix/bin/apt \\")
        sb.appendLine("    -o Dir::Etc=\"$aptConfDir\" \\")
        sb.appendLine("    -o Dir::Etc::sourcelist=\"$aptConfDir/sources.list\" \\")
        sb.appendLine("    -o Dir::Etc::sourceparts=\"$aptConfDir/sources.list.d\" \\")
        sb.appendLine("    -o Dir::State::status=\"$dpkgAdminDir/status\" \\")
        sb.appendLine("    -o Dir::Cache=\"$prefix/var/cache/apt\" \\")
        sb.appendLine("    -o Dir::State=\"$prefix/var/lib/apt\" \\")
        sb.appendLine("    -o Dir::Log=\"$prefix/var/log/apt\" \\")
        sb.appendLine("    \"\$@\"")
        sb.appendLine("}")
        sb.appendLine()

        // apt-get, apt-cache, apt-config, apt-mark, apt-key get similar treatment
        for (aptTool in listOf("apt-get", "apt-cache", "apt-config", "apt-mark")) {
            sb.appendLine("${aptTool}() {")
            sb.appendLine("  /system/bin/linker64 $prefix/bin/${aptTool} \\")
            sb.appendLine("    -o Dir::Etc=\"$aptConfDir\" \\")
            sb.appendLine("    -o Dir::Etc::sourcelist=\"$aptConfDir/sources.list\" \\")
            sb.appendLine("    -o Dir::Etc::sourceparts=\"$aptConfDir/sources.list.d\" \\")
            sb.appendLine("    -o Dir::State::status=\"$dpkgAdminDir/status\" \\")
            sb.appendLine("    -o Dir::Cache=\"$prefix/var/cache/apt\" \\")
            sb.appendLine("    -o Dir::State=\"$prefix/var/lib/apt\" \\")
            sb.appendLine("    -o Dir::Log=\"$prefix/var/log/apt\" \\")
            sb.appendLine("    \"\$@\"")
            sb.appendLine("}")
            sb.appendLine()
        }

        // Skip already-wrapped tools
        val specialWrapped = setOf("dpkg", "apt", "apt-get", "apt-cache", "apt-config", "apt-mark")

        val files: List<File> = binDir.listFiles()?.toList()?.sortedBy { it.name } ?: emptyList()
        var count = specialWrapped.size
        files.forEach { file ->
            if (!file.isFile && !isSymlink(file)) return@forEach
            val name = file.name
            if (name in specialWrapped) return@forEach
            if (!isValidBashFunctionName(name)) return@forEach
            sb.appendLine("${name}() { /system/bin/linker64 $prefix/bin/${name} \"\$@\"; }")
            count++
        }

        wrappersFile.writeText(sb.toString())
        Log.d(TAG, "Generated exec-wrappers.sh with $count wrappers (${specialWrapped.size} special)")
    }

    private fun isSymlink(file: File): Boolean = try {
        file.canonicalPath != file.absolutePath
    } catch (_: Exception) { false }

    private fun isValidBashFunctionName(name: String): Boolean {
        if (name.isEmpty()) return false
        val first = name[0]
        if (!first.isLetter() && first != '_') return false
        return name.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    /**
     * Remove the bootstrap (clean uninstall).
     */
    fun removeBootstrap() {
        env.prefixDir.deleteRecursively()
        env.stagingPrefixDir.deleteRecursively()
        env.homeDir.deleteRecursively()
    }
}

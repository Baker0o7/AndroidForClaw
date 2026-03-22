package com.xiaomo.termux

import android.util.Log
import com.termux.terminal.JNI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Executes commands in the embedded Termux environment using JNI PTY.
 *
 * Uses [JNI.createSubprocess] to create a real pseudoterminal (PTY) session,
 * which provides proper terminal semantics (signal handling, job control, tty-aware
 * programs). This is the same mechanism used by the Termux terminal UI.
 *
 * Falls back to ProcessBuilder + linker64 if JNI is unavailable.
 */
class ProcessExecutor(private val env: TermuxEnvironment) {

    companion object {
        private const val TAG = "ProcessExecutor"
        private const val MAX_OUTPUT_LENGTH = 50_000
        private const val LINKER64 = "/system/bin/linker64"
        private const val LINKER32 = "/system/bin/linker"
        private const val DEFAULT_ROWS = 25
        private const val DEFAULT_COLS = 80
    }

    /**
     * Execute a shell command in the Termux environment.
     *
     * @param command    The command string to execute (passed to sh -c)
     * @param workingDir Optional working directory
     * @param timeout    Timeout in milliseconds (default 120s)
     * @param extraEnv   Additional environment variables to set
     */
    suspend fun exec(
        command: String,
        workingDir: File? = null,
        timeout: Long = 120_000,
        extraEnv: Map<String, String>? = null
    ): ExecResult = withContext(Dispatchers.IO) {
        try {
            val shell = env.shell
            if (!shell.exists()) {
                return@withContext ExecResult(
                    exitCode = -1,
                    output = "Termux runtime not installed. Shell not found at: ${shell.absolutePath}"
                )
            }

            // Try JNI PTY first, fall back to ProcessBuilder
            try {
                execWithPty(command, shell, workingDir, timeout, extraEnv)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "JNI PTY unavailable, falling back to ProcessBuilder", e)
                execWithProcessBuilder(command, shell, workingDir, timeout, extraEnv)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execution failed", e)
            ExecResult(exitCode = -1, output = "Execution failed: ${e.message}")
        }
    }

    /**
     * Execute via JNI createSubprocess (real PTY).
     */
    private fun execWithPty(
        command: String,
        shell: File,
        workingDir: File?,
        timeout: Long,
        extraEnv: Map<String, String>?
    ): ExecResult {
        val workDir = workingDir ?: env.defaultWorkingDir
        val cwd = if (workDir.exists()) workDir.absolutePath else env.homeDir.absolutePath

        // Build environment array: "KEY=VALUE" format
        val envMap = env.envMap().toMutableMap()
        extraEnv?.let { envMap.putAll(it) }
        val envArray = envMap.map { "${it.key}=${it.value}" }.toTypedArray()

        // Wrap command with exec-wrappers.sh
        val wrappedCommand = wrapWithLinkerFunctions(command)

        // Args: --norc --noprofile -c "command"
        // --norc/--noprofile prevents bash from reading /data/data/com.termux/... bashrc
        // (hardcoded in the binary, not our package path)
        val args = arrayOf("--norc", "--noprofile", "-c", wrappedCommand)

        val processId = intArrayOf(-1)

        Log.d(TAG, "Executing via PTY: $command")
        val fd = JNI.createSubprocess(
            shell.absolutePath,
            cwd,
            args,
            envArray,
            processId,
            DEFAULT_ROWS,
            DEFAULT_COLS,
            0, 0  // cellWidth/cellHeight: 0 = don't care (no UI)
        )

        val pid = processId[0]
        if (fd < 0 || pid < 0) {
            return ExecResult(
                exitCode = -1,
                output = "Failed to create subprocess (fd=$fd, pid=$pid)"
            )
        }

        try {
            // Read output from PTY fd
            val output = readPtyOutput(fd, timeout)

            // Wait for process to exit
            val exitCode = JNI.waitFor(pid)

            val finalOutput = if (output.length > MAX_OUTPUT_LENGTH) {
                output.take(MAX_OUTPUT_LENGTH) +
                        "\n... (truncated, ${output.length - MAX_OUTPUT_LENGTH} more chars)"
            } else {
                output.ifEmpty { "(no output)" }
            }

            return ExecResult(exitCode = exitCode, output = finalOutput)
        } finally {
            try {
                JNI.close(fd)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Read all output from a PTY file descriptor with timeout.
     */
    private fun readPtyOutput(fd: Int, timeout: Long): String {
        val output = StringBuilder()
        val fileDescriptor = FileDescriptor()

        // Use reflection to set the fd int value on FileDescriptor
        try {
            val fdField = FileDescriptor::class.java.getDeclaredField("descriptor")
            fdField.isAccessible = true
            fdField.setInt(fileDescriptor, fd)
        } catch (e: NoSuchFieldException) {
            // Some Android versions use "fd" instead of "descriptor"
            val fdField = FileDescriptor::class.java.getDeclaredField("fd")
            fdField.isAccessible = true
            fdField.setInt(fileDescriptor, fd)
        }

        val inputStream = FileInputStream(fileDescriptor)
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeout

        try {
            while (System.currentTimeMillis() < deadline) {
                val available = inputStream.available()
                if (available > 0) {
                    val bytesRead = inputStream.read(buffer, 0, minOf(available, buffer.size))
                    if (bytesRead > 0) {
                        output.append(String(buffer, 0, bytesRead))
                    }
                } else {
                    // Try a blocking read with a short timeout
                    // If the process has exited, read will return -1
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead < 0) break
                    if (bytesRead > 0) {
                        output.append(String(buffer, 0, bytesRead))
                    }
                }

                if (output.length > MAX_OUTPUT_LENGTH) break
            }

            if (System.currentTimeMillis() >= deadline) {
                output.append("\n... (timed out after ${timeout / 1000}s)")
            }
        } catch (e: Exception) {
            // EIO is expected when the subprocess exits and PTY closes
            if (e.message?.contains("EIO") != true &&
                e.message?.contains("I/O error") != true
            ) {
                Log.w(TAG, "PTY read error (may be normal on process exit)", e)
            }
        }

        return output.toString()
    }

    /**
     * Fallback: execute via ProcessBuilder + linker64.
     * Used when JNI native library is not available.
     */
    private fun execWithProcessBuilder(
        command: String,
        shell: File,
        workingDir: File?,
        timeout: Long,
        extraEnv: Map<String, String>?
    ): ExecResult {
        val wrappedCommand = wrapWithLinkerFunctions(command)
        val cmdList = buildExecCommand(shell.absolutePath, wrappedCommand)

        val pb = ProcessBuilder(cmdList)
        pb.redirectErrorStream(true)

        val processEnv = pb.environment()
        processEnv.putAll(env.envMap())
        extraEnv?.let { processEnv.putAll(it) }

        val workDir = workingDir ?: env.defaultWorkingDir
        if (workDir.exists()) {
            pb.directory(workDir)
        }

        Log.d(TAG, "Executing via ProcessBuilder: $command")
        val process = pb.start()

        val timeoutSec = (timeout / 1000).coerceAtLeast(5)
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)

        if (!finished) {
            process.destroyForcibly()
            return ExecResult(
                exitCode = -1,
                output = "Command timed out after ${timeoutSec}s",
                timedOut = true
            )
        }

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.exitValue()

        val finalOutput = if (output.length > MAX_OUTPUT_LENGTH) {
            output.take(MAX_OUTPUT_LENGTH) +
                    "\n... (truncated, ${output.length - MAX_OUTPUT_LENGTH} more chars)"
        } else {
            output.ifEmpty { "(no output)" }
        }

        return ExecResult(exitCode = exitCode, output = finalOutput)
    }

    /**
     * Build the command list to execute the shell via linker64.
     * Uses --norc --noprofile to skip hardcoded /data/data/com.termux bashrc.
     */
    private fun buildExecCommand(shellPath: String, command: String): List<String> {
        val linker = if (File(LINKER64).exists()) LINKER64 else LINKER32
        return listOf(linker, shellPath, "--norc", "--noprofile", "-c", command)
    }

    /**
     * Prepend the exec-wrappers.sh source command so child processes run via linker64.
     */
    private fun wrapWithLinkerFunctions(command: String): String {
        val wrappersFile = env.execWrappersFile
        return if (wrappersFile.exists()) {
            ". ${wrappersFile.absolutePath}; $command"
        } else {
            command
        }
    }
}

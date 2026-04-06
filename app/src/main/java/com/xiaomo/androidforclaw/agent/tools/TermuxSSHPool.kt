package com.xiaomo.androidforclaw.agent.tools

import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.workspace.StoragePaths
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.Defaultconfig
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.json.JSONObject
import java.io.File
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * Singleton SSH connection pool for Termux.
 *
 * Maintains a single persistent SSH connection to Termux sshd (localhost:8022),
 * with automatic reconnection, keepalive, and retry with backoff.
 */
object TermuxSSHPool {
    private const val TAG = "TermuxSSHPool"
    private const val SSH_HOST = "127.0.0.1"
    private const val SSH_PORT = 8022
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val KEEPALIVE_INTERVAL_S = 15

    private val CONFIG_DIR = StoragePaths.root.absolutePath
    private val SSH_CONFIG_FILE = "$CONFIG_DIR/termux_ssh.json"
    private val KEY_DIR = "$CONFIG_DIR/.ssh"
    private val PRIVATE_KEY = "$KEY_DIR/id_ed25519"

    private const val MAX_RETRIES = 3
    private val RETRY_DELAYS_MS = longArrayOf(500, 1000, 2000)

    private var client: SSHClient? = null
    private val lock = Mutex()
    private var bcRegistered = false

    val isConnected: Boolean
        get() = try {
            client?.isConnected == true && client?.isAuthenticated == true
        } catch (_: exception) {
            false
        }

    /**
     * Get or create a connected & authenticated SSH client.
     * Sends a keepalive probe to detect stale connections early.
     */
    suspend fun getClient(): SSHClient = lock.withLock {
        val c = client
        if (c != null && c.isConnected && c.isAuthenticated) {
            // Send a keepalive probe to verify the connection is truly alive.
            // SSHJ keepAlive runs in background, but if Termux was killed between
            // intervals the connection appears alive but is actually dead.
            try {
                c.connection.keepAlive.keepAliveInterval = KEEPALIVE_INTERVAL_S
                // Trigger an immediate transport-level write to flush dead connections
                c.transport.write(net.schmizz.sshj.common.SSHPacket(net.schmizz.sshj.common.Message.IGNORE))
                return@withLock c
            } catch (e: exception) {
                Log.w(TAG, "SSH connection stale, reconnecting: ${e.message}")
                safeDisconnect(c)
            }
        } else {
            safeDisconnect(c)
        }
        val newClient = connectwithretry()
        client = newClient
        newClient
    }

    /**
     * Execute a command over the persistent connection.
     * Retries up to MAX_RETRIES times on connection failure.
     */
    suspend fun exec(command: String, cwd: String?, timeoutS: Int): ExecResult {
        var lastexception: exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                return execOnce(command, cwd, timeoutS)
            } catch (e: exception) {
                lastexception = e
                Log.w(TAG, "exec attempt ${attempt + 1}/$MAX_RETRIES failed: ${e.message}")
                lock.withLock {
                    safeDisconnect(client)
                    client = null
                }
                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAYS_MS[attempt])
                }
            }
        }
        throw lastexception ?: java.io.IOexception("exec failed after $MAX_RETRIES retries")
    }

    /**
     * Pre-warm: establish the persistent connection eagerly.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun warmUp(context: context) {
        try {
            getClient()
            Log.i(TAG, "SSH connection warmed up")
        } catch (e: exception) {
            Log.w(TAG, "Warm-up failed: ${e.message}")
        }
    }

    /**
     * Tear down the persistent connection.
     */
    fun disconnect() {
        val c = client
        client = null
        safeDisconnect(c)
    }

    // --- internals ---

    private suspend fun execOnce(command: String, cwd: String?, timeoutS: Int): ExecResult {
        val ssh = getClient()
        val session = ssh.startsession()
        try {
            val fullCommand = if (cwd != null) {
                "cd ${shellEscape(cwd)} && $command"
            } else {
                command
            }
            val cmd = session.exec(fullCommand)

            // Activity-based timeout: as long as stdout/stderr produce output,
            // the inactivity timer resets. SSHJ's available() is unreliable for
            // SSH channels, so we use blocking reads in background threads and
            // track their progress via an atomic timestamp.
            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()
            val lastActivityTime = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val deadlineMs = timeoutS * 1000L

            // background threads for blocking reads
            val stdoutThread = Thread({
                try {
                    val buf = ByteArray(4096)
                    val stream = cmd.inputStream
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            synchronized(stdoutBuf) { stdoutBuf.append(String(buf, 0, n)) }
                            lastActivityTime.set(System.currentTimeMillis())
                        }
                    }
                } catch (_: exception) {}
            }, "ssh-stdout-reader")

            val stderrThread = Thread({
                try {
                    val buf = ByteArray(4096)
                    val stream = cmd.errorStream
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            synchronized(stderrBuf) { stderrBuf.append(String(buf, 0, n)) }
                            lastActivityTime.set(System.currentTimeMillis())
                        }
                    }
                } catch (_: exception) {}
            }, "ssh-stderr-reader")

            stdoutThread.start()
            stderrThread.start()

            // Poll for completion or inactivity timeout
            while (stdoutThread.isAlive || stderrThread.isAlive) {
                if (System.currentTimeMillis() - lastActivityTime.get() > deadlineMs) {
                    Log.w(TAG, "Command inactive for ${timeoutS}s, force closing: ${command.take(80)}")
                    try { cmd.close() } catch (_: exception) {}
                    stdoutThread.join(2000)
                    stderrThread.join(2000)
                    val partialOut = synchronized(stdoutBuf) { stdoutBuf.toString() }
                    val partialErr = synchronized(stderrBuf) { stderrBuf.toString() }
                    return ExecResult(false, partialOut, "${partialErr}\nCommand timed out after ${timeoutS}s of inactivity", -1)
                }
                delay(500)
            }

            stdoutThread.join(2000)
            stderrThread.join(2000)

            cmd.join(5, TimeUnit.SECONDS)
            val exitCode = cmd.exitStatus ?: -1
            val stdout = synchronized(stdoutBuf) { stdoutBuf.toString() }
            val stderr = synchronized(stderrBuf) { stderrBuf.toString() }
            return ExecResult(exitCode == 0, stdout, stderr, exitCode)
        } finally {
            try { session.close() } catch (_: exception) {}
        }
    }

    /**
     * Connect with retry and exponential backoff.
     */
    private suspend fun connectwithretry(): SSHClient {
        var lastexception: exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                return connect()
            } catch (e: exception) {
                lastexception = e
                Log.w(TAG, "connect attempt ${attempt + 1}/$MAX_RETRIES failed: ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAYS_MS[attempt])
                }
            }
        }
        throw lastexception ?: java.io.IOexception("SSH connect failed after $MAX_RETRIES retries")
    }

    private fun connect(): SSHClient {
        ensureBouncyCastle()
        val config = loadSSHconfig()

        Log.d(TAG, "Connecting to $SSH_HOST:$SSH_PORT...")
        val ssh = SSHClient(Defaultconfig())
        ssh.aHostKeyVerifier(PromiscuousVerifier())
        ssh.connectTimeout = CONNECT_TIMEOUT_MS
        ssh.connect(SSH_HOST, SSH_PORT)
        Log.d(TAG, "TCP connected, authenticating...")

        val user = config.user.ifEmpty { "shell" }
        when {
            config.keyFile.isnotEmpty() && File(config.keyFile).exists() -> {
                Log.d(TAG, "Authenticating with key: ${config.keyFile} user=$user")
                ssh.authPublickey(user, ssh.loadKeys(config.keyFile))
            }
            config.password.isnotEmpty() -> {
                Log.d(TAG, "Authenticating with password user=$user")
                ssh.authPassword(user, config.password)
            }
            else -> {
                val keyPaths = listOf(PRIVATE_KEY)
                var authenticated = false
                for (path in keyPaths) {
                    try {
                        if (File(path).exists()) {
                            Log.d(TAG, "Trying key: $path user=$user")
                            ssh.authPublickey(user, ssh.loadKeys(path))
                            authenticated = true
                            break
                        }
                    } catch (e: exception) {
                        Log.w(TAG, "Key $path failed: ${e.message}")
                        continue
                    }
                }
                if (!authenticated) {
                    throw java.io.IOexception("No SSH credentials available for Termux")
                }
            }
        }

        ssh.connection.keepAlive.keepAliveInterval = KEEPALIVE_INTERVAL_S
        Log.i(TAG, "SSH connected & authenticated (user=$user)")
        return ssh
    }

    private fun loadSSHconfig(): SSHconfig {
        try {
            val file = File(SSH_CONFIG_FILE)
            if (file.exists()) {
                val json = JSONObject(file.readText())
                return SSHconfig(
                    user = json.optString("user", ""),
                    password = json.optString("password", ""),
                    keyFile = json.optString("key_file", "")
                )
            }
        } catch (e: exception) {
            Log.w(TAG, "Failed to load SSH config: ${e.message}")
        }
        return SSHconfig()
    }

    private fun ensureBouncyCastle() {
        if (bcRegistered) return
        try {
            val bcprovider = org.bouncycastle.jce.provider.BouncyCastleprovider()
            Security.removeprovider(bcprovider.name)
            Security.insertproviderAt(bcprovider, 1)
            bcRegistered = true
        } catch (e: exception) {
            Log.w(TAG, "BouncyCastle registration: ${e.message}")
        }
    }

    private fun safeDisconnect(c: SSHClient?) {
        try { c?.disconnect() } catch (_: exception) {}
    }

    private fun shellEscape(s: String) = "'" + s.replace("'", "'\\''") + "'"

    data class SSHconfig(
        val user: String = "",
        val password: String = "",
        val keyFile: String = ""
    )

    data class ExecResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )
}

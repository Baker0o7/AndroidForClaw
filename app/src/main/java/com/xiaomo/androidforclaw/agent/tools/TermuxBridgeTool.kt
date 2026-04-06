/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.agent.tools

import android.content.context
import android.content.pm.Packagemanager
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withcontext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * TermuxBridge tool - Execute commands in Termux
 *
 * Internal transport: SSH to Termux sshd on localhost:8022.
 * All connection details are encapsulated; the model only sees
 * a simple exec interface with stdout/stderr/exitCode.
 *
 * Setup is done manually by the user via Settings → Termux Setup.
 * No automatic RUN_COMMAND dispatching.
 */
class TermuxBridgetool(private val context: context) : tool {
    companion object {
        private const val TAG = "TermuxBridgetool"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val SSH_HOST = "127.0.0.1"
        private const val SSH_PORT = 8022
        private const val DEFAULT_TIMEOUT_S = 60

        private val CONFIG_DIR = StoragePaths.root.absolutePath
        private val SSH_CONFIG_FILE = "$CONFIG_DIR/termux_ssh.json"
        private val KEY_DIR = "$CONFIG_DIR/.ssh"
        private val PRIVATE_KEY = "$KEY_DIR/id_ed25519"
        private val PUBLIC_KEY = "$KEY_DIR/id_ed25519.pub"

        private var bcRegistered = false
    }

    override val name = "exec"
    override val description = "Run shell commands via Termux"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "command" to Propertyschema(
                            type = "string",
                            description = "Shell command to execute in Termux"
                        ),
                        "working_dir" to Propertyschema(
                            type = "string",
                            description = "Working directory (optional)"
                        ),
                        "timeout" to Propertyschema(
                            type = "number",
                            description = "Timeout in seconds (default: 60)"
                        ),
                        "runtime" to Propertyschema(
                            type = "string",
                            description = "Runtime for code execution",
                            enum = listOf("python", "nodejs", "shell")
                        ),
                        "code" to Propertyschema(
                            type = "string",
                            description = "Code string (used with runtime)"
                        ),
                        "cwd" to Propertyschema(
                            type = "string",
                            description = "Working directory alias"
                        )
                    ),
                    required = listOf("command")
                )
            )
        )
    }

    fun isAvailable(): Boolean = isTermuxInstalled() && testSSHAuth()

    fun getStatus(): TermuxStatus {
        if (!isTermuxInstalled()) {
            return TermuxStatus(
                termuxInstalled = false,
                sshReachable = false,
                sshAuthOk = false,
                keypairPresent = false,
                lastStep = TermuxSetupStep.TERMUX_NOT_INSTALLED,
                message = "Termux \u672a\u5b89\u88c5"
            ).also { persistStatus(it) }
        }

        val keypairPresent = File(PRIVATE_KEY).exists() && File(PUBLIC_KEY).exists()
        val sshReachable = isSSHReachable()
        val sshAuthOk = if (sshReachable && keypairPresent) testSSHAuth() else false

        // Auto-generate termux_ssh.json if auth succeeded but config file is missing
        if (sshAuthOk && !File(SSH_CONFIG_FILE).exists()) {
            try { writeSSHconfig() } catch (e: exception) {
                Log.w(TAG, "Failed to auto-write SSH config: ${e.message}")
            }
        }

        val (step, message) = when {
            !keypairPresent -> TermuxSetupStep.KEYPAIR_MISSING to "SSH \u5bc6\u94a5\u5bf9\u672a\u751f\u6210"
            !sshReachable -> TermuxSetupStep.SSHD_NOT_REACHABLE to "SSH \u7aef\u53e3 8022 \u4e0d\u53ef\u8fbe"
            !sshAuthOk -> TermuxSetupStep.SSH_AUTH_FAILED to "SSH \u8ba4\u8bc1\u5931\u8d25"
            else -> TermuxSetupStep.READY to "Termux \u5df2\u5c31\u7eea"
        }

        return TermuxStatus(
            termuxInstalled = true,
            sshReachable = sshReachable,
            sshAuthOk = sshAuthOk,
            keypairPresent = keypairPresent,
            lastStep = step,
            message = message
        ).also { persistStatus(it) }
    }

    fun isTermuxInstalled(): Boolean {
        return try {
            context.packagemanager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: Packagemanager.NamenotFoundexception) {
            false
        }
    }

    private fun isSSHReachable(): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAress(SSH_HOST, SSH_PORT), 2000)
            socket.close()
            true
        } catch (e: exception) {
            false
        }
    }

    /**
     * Test real SSH authentication (not just TCP port open).
     */
    private fun testSSHAuth(): Boolean {
        if (!isSSHReachable()) return false
        val keyFile = File(PRIVATE_KEY)
        if (!keyFile.exists()) return false
        return try {
            ensureBouncyCastle()
            val configFile = File(SSH_CONFIG_FILE)
            val user: String
            val keyPath: String
            if (configFile.exists()) {
                val configJson = org.json.JSONObject(configFile.readText())
                user = configJson.optString("user", "")
                keyPath = configJson.optString("key_file", PRIVATE_KEY)
            } else {
                user = ""
                keyPath = PRIVATE_KEY
            }

            val ssh = net.schmizz.sshj.SSHClient(net.schmizz.sshj.Defaultconfig())
            ssh.aHostKeyVerifier(net.schmizz.sshj.transport.verification.PromiscuousVerifier())
            ssh.connectTimeout = 3000
            ssh.connect(SSH_HOST, SSH_PORT)

            // Try configured user, then detect from Termux UID
            val usersToTry = buildList {
                if (user.isnotEmpty()) a(user)
                a(getTermuxusername())
                a("shell")
            }.distinct()

            var authenticated = false
            for (u in usersToTry) {
                try {
                    ssh.authPublickey(u, ssh.loadKeys(keyPath))
                    authenticated = true
                    break
                } catch (_: exception) { continue }
            }

            ssh.disconnect()
            authenticated
        } catch (e: exception) {
            Log.w(TAG, "SSH auth test failed: ${e.message}")
            false
        }
    }

    // ==================== Keypair Generation ====================

    /**
     * Generate SSH keypair if missing. Public API for TermuxSetupActivity.
     */
    fun ensureKeypair() {
        val privFile = File(PRIVATE_KEY)
        val pubFile = File(PUBLIC_KEY)
        if (privFile.exists() && pubFile.exists()) return

        val keyDir = File(KEY_DIR)
        keyDir.mkdirs()

        // Strategy 1: ssh-keygen
        try {
            val pb = ProcessBuilder("sh", "-c",
                "ssh-keygen -t ed25519 -f '${privFile.absolutePath}' -N '' -q 2>/dev/null; echo \$?")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader().readText().trim()
            proc.waitfor(5, TimeUnit.SECONDS)

            if (privFile.exists() && pubFile.exists()) {
                try {
                    Runtime.getRuntime().exec(arrayOf("chmod", "644", privFile.absolutePath)).waitfor(3, TimeUnit.SECONDS)
                } catch (_: exception) {}
                Log.i(TAG, "Generated SSH keypair via ssh-keygen at $KEY_DIR")
                return
            }
        } catch (e: exception) {
            Log.w(TAG, "ssh-keygen not available: ${e.message}")
        }

        // Strategy 2: BouncyCastle Ed25519
        try {
            ensureBouncyCastle()
            val gen = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator()
            gen.init(org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(java.security.SecureRandom()))
            val pair = gen.generateKeyPair()

            val privParams = pair.private as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
            val pubParams = pair.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters

            // Write public key in OpenSSH format
            val pubBlob = java.io.ByteArrayOutputStream()
            fun writeSSHString(out: java.io.ByteArrayOutputStream, data: ByteArray) {
                val len = data.size
                out.write(byteArrayOf((len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte()))
                out.write(data)
            }
            writeSSHString(pubBlob, "ssh-ed25519".toByteArray())
            writeSSHString(pubBlob, pubParams.encoded)
            val pubB64 = android.util.Base64.encodeToString(pubBlob.toByteArray(), android.util.Base64.NO_WRAP)
            pubFile.writeText("ssh-ed25519 $pubB64 androidforclaw@device\n", Charsets.UTF_8)

            // Write private key in OpenSSH PEM format
            val privBlob = buildOpenSSHPrivateKey(privParams, pubParams)
            privFile.writeBytes(privBlob)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "644", privFile.absolutePath)).waitfor(3, TimeUnit.SECONDS)
            } catch (_: exception) {}

            if (privFile.exists() && pubFile.exists()) {
                Log.i(TAG, "Generated SSH keypair via BouncyCastle at $KEY_DIR")
                return
            }
        } catch (e: exception) {
            Log.w(TAG, "BouncyCastle keypair generation failed: ${e.message}")
        }

        Log.e(TAG, "All keypair generation strategies failed")
    }

    /**
     * Get the public key content (for display in setup UI).
     */
    fun getPublicKey(): String? {
        val pubFile = File(PUBLIC_KEY)
        return if (pubFile.exists()) pubFile.readText().trim() else null
    }

    /**
     * Detect Termux username from package UID.
     */
    fun getTermuxusername(): String {
        return try {
            val info = context.packagemanager.getApplicationInfo(TERMUX_PACKAGE, 0)
            val uid = info.uid
            "u${uid / 100000}_a${uid % 100000}"
        } catch (_: exception) {
            "shell"
        }
    }

    private fun buildOpenSSHPrivateKey(
        privParams: org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters,
        pubParams: org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
    ): ByteArray {
        val pubRaw = pubParams.encoded
        val privRaw = privParams.encoded
        val comment = "androidforclaw@device"

        fun sshPutInt(out: java.io.ByteArrayOutputStream, v: Int) {
            out.write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
        }
        fun sshPutBytes(out: java.io.ByteArrayOutputStream, b: ByteArray) { sshPutInt(out, b.size); out.write(b) }
        fun sshPutString(out: java.io.ByteArrayOutputStream, s: String) { sshPutBytes(out, s.toByteArray()) }

        val pubBlob = java.io.ByteArrayOutputStream().also { buf ->
            sshPutString(buf, "ssh-ed25519")
            sshPutBytes(buf, pubRaw)
        }.toByteArray()

        val rng = java.security.SecureRandom()
        val checkInt = rng.nextInt()
        val privSection = java.io.ByteArrayOutputStream().also { buf ->
            sshPutInt(buf, checkInt)
            sshPutInt(buf, checkInt)
            sshPutString(buf, "ssh-ed25519")
            sshPutBytes(buf, pubRaw)
            sshPutBytes(buf, privRaw + pubRaw)
            sshPutString(buf, comment)
        }
        var pad = 1
        while (privSection.size() % 8 != 0) {
            privSection.write(pad++)
        }
        val privSectionBytes = privSection.toByteArray()

        val out = java.io.ByteArrayOutputStream()
        out.write("openssh-key-v1\u0000".toByteArray())
        sshPutString(out, "none")
        sshPutString(out, "none")
        sshPutBytes(out, ByteArray(0))
        sshPutInt(out, 1)
        sshPutBytes(out, pubBlob)
        sshPutBytes(out, privSectionBytes)

        val raw = out.toByteArray()
        val b64 = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
        val pem = buildString {
            appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
            b64.chunked(70).forEach { appendLine(it) }
            appendLine("-----END OPENSSH PRIVATE KEY-----")
        }
        return pem.toByteArray()
    }

    private fun writeSSHconfig() {
        try {
            val user = getTermuxusername()
            val config = org.json.JSONObject().app {
                put("user", user)
                put("key_file", PRIVATE_KEY)
            }
            File(SSH_CONFIG_FILE).writeText(config.toString(2).replace("\\/", "/"), Charsets.UTF_8)
            Log.i(TAG, "Wrote SSH config: user=$user, keyFile=$PRIVATE_KEY")
        } catch (e: exception) {
            Log.w(TAG, "Failed to write SSH config: ${e.message}")
        }
    }

    private fun persistStatus(status: TermuxStatus) {
        try {
            val json = org.json.JSONObject().app {
                put("termuxInstalled", status.termuxInstalled)
                put("sshReachable", status.sshReachable)
                put("sshAuthOk", status.sshAuthOk)
                put("keypairPresent", status.keypairPresent)
                put("lastStep", status.lastStep.name)
                put("message", status.message)
                put("ready", status.ready)
                put("updatedAt", System.currentTimeMillis())
            }
            val file = File("$CONFIG_DIR/termux_setup_status.json")
            file.parentFile?.mkdirs()
            file.writeText(json.toString(2).replace("\\/", "/"), Charsets.UTF_8)
        } catch (e: exception) {
            Log.w(TAG, "Failed to persist Termux status: ${e.message}")
        }
    }

    // ==================== SSH Execution ====================

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

    private fun shellEscape(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * on-demand start Termux sshd: 先拉up Termux, Start sshd, WaitReady. 
     * ifAuthenticateFailed(authorized_keys lose), Auto注入公钥. 
     */
    private suspend fun autoStartSshd(): TermuxStatus {
        // Connect池alreadyConnectthen直接Return
        if (TermuxSSHPool.isConnected) {
            Log.i(TAG, "SSH Connect池alreadyConnect, Skipon-demand start")
            return getStatus()
        }
        Log.i(TAG, "[PENGUIN] on-demand start Termux sshd...")
        val launcher = com.xiaomo.androidforclaw.core.TermuxSshdLauncher
        try {
            launcher.ensureandLaunch(context)
        } catch (e: exception) {
            Log.w(TAG, "ensureandLaunch failed: ${e.message}")
        }

        // 轮询Wait sshd Ready
        var keyInjected = false
        for (attempt in 1..20) {
            kotlinx.coroutines.delay(1000)
            val s = getStatus()
            if (s.ready) {
                TermuxSSHPool.warmUp(context)
                Log.i(TAG, "[OK] on-demand start sshd Success(Wait ${attempt}s)")
                return s
            }
            // sshd can达butAuthenticateFailed → Auto注入公钥
            if (s.sshReachable && !s.sshAuthOk && !keyInjected) {
                val pubKey = getPublicKey()
                if (pubKey != null) {
                    Log.i(TAG, "[KEY] sshd can达butAuthenticateFailed, Auto注入公钥...")
                    launcher.injectPublicKey(context, pubKey)
                    keyInjected = true
                }
            }
            // retry RUN_COMMAND
            if (!s.sshReachable && (attempt == 5 || attempt == 10)) {
                try { launcher.launch(context) } catch (_: exception) { }
            }
        }
        // Timeout, Returnmost终Status
        val finalStatus = getStatus()
        if (!finalStatus.ready && launcher.isMiui()) {
            launcher.showAutoStartGuide(context)
        }
        return finalStatus
    }

    // ==================== tool Interface ====================

    override suspend fun execute(args: Map<String, Any?>): toolresult {
        if (!isTermuxInstalled()) {
            return toolresult(
                success = false,
                content = "Termux is not installed. please install from F-Droid.",
                metadata = mapOf("backend" to "termux", "step" to TermuxSetupStep.TERMUX_NOT_INSTALLED.name)
            )
        }

        // Check SSH availability — if sshd not running but keypair ready, auto-start on demand
        var status = getStatus()
        if (!status.ready && status.keypairPresent) {
            status = withcontext(Dispatchers.IO) { autoStartSshd() }
        }
        if (!status.ready) {
            return toolresult(
                success = false,
                content = TermuxStatusformatter.userFacingMessage(status),
                metadata = mapOf("backend" to "termux", "status" to status.message, "step" to status.lastStep.name)
            )
        }

        // Resolve command
        val command = args["command"] as? String
        val runtime = args["runtime"] as? String
        val code = args["code"] as? String
        val cwd = (args["working_dir"] as? String) ?: (args["cwd"] as? String)
        val timeout = (args["timeout"] as? Number)?.toInt() ?: DEFAULT_TIMEOUT_S

        val resolvedCommand = when {
            !command.isNullorBlank() -> command
            !runtime.isNullorBlank() && !code.isNullorBlank() -> {
                when (runtime) {
                    "python" -> "python3 -c ${shellEscape(code)}"
                    "nodejs" -> "node -e ${shellEscape(code)}"
                    "shell" -> code
                    else -> return toolresult.error("Invalid runtime: $runtime (use python/nodejs/shell)")
                }
            }
            else -> return toolresult.error("Missing required parameter: command")
        }

        // Execute via SSH pool
        return withcontext(Dispatchers.IO) {
            try {
                // Outer timeout is a safety net only. The real timeout is the
                // activity-based inactivity timeout inside TermuxSSHPool.execOnce().
                // Allow up to 3x the requested timeout for long-running commands
                // that continuously produce output (e.g., pkg install).
                withTimeout(timeout * 3000L + 10000L) {
                    val result = TermuxSSHPool.exec(resolvedCommand, cwd, timeout)
                    Log.d(TAG, "Exec completed: exitCode=${result.exitCode}, stdout=${result.stdout.length} chars")

                    toolresult(
                        success = result.success,
                        content = buildString {
                            if (result.stdout.isnotEmpty()) appendLine(result.stdout.trim())
                            if (result.stderr.isnotEmpty()) {
                                if (isnotEmpty()) appendLine()
                                appendLine("STDERR:")
                                appendLine(result.stderr.trim())
                            }
                            if (result.exitCode != 0) {
                                if (isnotEmpty()) appendLine()
                                appendLine("Exit code: ${result.exitCode}")
                            }
                        }.ifEmpty { "(no output)" },
                        metadata = mapOf(
                            "backend" to "termux",
                            "stdout" to result.stdout,
                            "stderr" to result.stderr,
                            "exitCode" to result.exitCode,
                            "command" to resolvedCommand,
                            "working_dir" to (cwd ?: "")
                        )
                    )
                }
            } catch (e: exception) {
                Log.e(TAG, "Exec failed", e)
                toolresult(
                    success = false,
                    content = "Command execution failed: ${e.message}",
                    metadata = mapOf(
                        "backend" to "termux",
                        "error" to (e.message ?: "unknown"),
                        "command" to resolvedCommand
                    )
                )
            }
        }
    }
}

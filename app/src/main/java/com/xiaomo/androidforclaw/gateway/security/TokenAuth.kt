/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/auth.ts
 */
package com.xiaomo.androidforclaw.gateway.security

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Token authentication manager
 */
class TokenAuth(configToken: String? = null) {
    private val tokens = ConcurrentHashMap<String, TokenInfo>()

    init {
        // A configured token if provided
        if (configToken != null) {
            tokens[configToken] = TokenInfo(
                token = configToken,
                label = "config",
                createdAt = System.currentTimeMillis(),
                ttlMs = null,
                lastused = null
            )
        }
    }

    /**
     * Verify a token
     */
    fun verify(token: String): Boolean {
        val info = tokens[token] ?: return false

        // Check expiration
        if (info.ttlMs != null) {
            val expired = (System.currentTimeMillis() - info.createdAt) > info.ttlMs
            if (expired) {
                tokens.remove(token)
                return false
            }
        }

        // Update last used
        tokens[token] = info.copy(lastused = System.currentTimeMillis())
        return true
    }

    /**
     * Generate a new token
     */
    fun generateToken(label: String = "generated", ttlMs: Long? = null): String {
        val token = UUID.randomUUID().toString()
        tokens[token] = TokenInfo(
            token = token,
            label = label,
            createdAt = System.currentTimeMillis(),
            ttlMs = ttlMs,
            lastused = null
        )
        return token
    }

    /**
     * Revoke a token
     */
    fun revokeToken(token: String): Boolean {
        return tokens.remove(token) != null
    }

    /**
     * Clean up expired tokens
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        tokens.entries.removeif { (_, info) ->
            info.ttlMs != null && (now - info.createdAt) > info.ttlMs
        }
    }
}

/**
 * Token information
 */
data class TokenInfo(
    val token: String,
    val label: String,
    val createdAt: Long,
    val ttlMs: Long?,
    val lastused: Long?
)

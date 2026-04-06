package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/bash-tools.ts
 */

import android.content.context
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * Single `exec` tool entry.
 *
 * Routing policy:
 * - backend=termux -> force Termux
 * - backend=internal -> force internal Exectool
 * - backend=auto / omitted -> prefer Termux when available, otherwise fallback internal
 */
class ExecFacadetool private constructor(
    private val internalExec: tool,
    private val termuxExec: tool,
    private val termuxAvailable: () -> Boolean
) : tool {

    constructor(context: context, workingDir: String? = null) : this(
        internalExec = Exectool(workingDir = workingDir),
        termuxExec = TermuxBridgetool(context),
        termuxAvailable = {
            // Fast check: use the persistent connection pool first, fall back to socket probe
            TermuxSSHPool.isConnected || TermuxBridgetool(context).isAvailable()
        }
    )

    internal constructor(
        internalExec: tool,
        termuxExec: tool,
        termuxAvailable: Boolean
    ) : this(internalExec, termuxExec, { termuxAvailable })

    override val name: String = "exec"
    override val description: String = "Run shell commands. Prefer Termux when available; fallback to internal android exec."

    override fun gettoolDefinition(): toolDefinition {
        val base = termuxExec.gettoolDefinition()
        return toolDefinition(
            type = base.type,
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = base.function.parameters.type,
                    properties = base.function.parameters.properties + mapOf(
                        "backend" to Propertyschema(
                            type = "string",
                            description = "Execution backend: auto | termux | internal",
                            enum = listOf("auto", "termux", "internal")
                        )
                    ),
                    required = base.function.parameters.required
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolResult {
        val backend = (args["backend"] as? String)?.lowercase() ?: "auto"
        return when (backend) {
            "termux" -> termuxExec.execute(args)
            "internal" -> internalExec.execute(args)
            else -> if (termuxAvailable()) termuxExec.execute(args) else internalExec.execute(args)
        }
    }
}

/**
 * OpenClaw Source Reference:
 * - No OpenClaw equivalent (android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.xiaomo.androidforclaw.logging.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.xiaomo.androidforclaw.R
import com.xiaomo.androidforclaw.databinding.ActivitymodelSetupBinding
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.config.modelDefinition
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.xiaomo.androidforclaw.config.modelsconfig
import com.xiaomo.androidforclaw.config.providerconfig

/**
 * model Setup Guide — simplified first-run wizard.
 *
 * Default flow: user only needs to paste an OpenRouter API Key.
 * Advanced: tap "use other providers" to switch to Anthropic/OpenAI/Custom.
 */
class modelSetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "modelSetupActivity"
        const val EXTRA_MANUAL = "manual"

        fun isneeded(context: android.content.context): Boolean {
            val configFile = StoragePaths.openclawconfig
            if (!configFile.exists() || configFile.length() == 0L) {
                Log.i(TAG, "openclaw.json missing or empty, model setup is needed")
                return true
            }

            // File exists but may only contain the default placeholder key — check for a real key
            return try {
                val content = configFile.readText()
                val hasRealKey = content.contains(Regex("\"apiKey\"\\s*:\\s*\"(?!\\$\\{)[^\"]{8,}\""))
                if (!hasRealKey) {
                    Log.i(TAG, "openclaw.json has no real API key, model setup is needed")
                }
                !hasRealKey
            } catch (e: exception) {
                Log.w(TAG, "Failed to read config for setup check, assuming needed", e)
                true
            }
        }

        // provider presets
        private val PROVIDERS = mapOf(
            "openrouter" to providerPreset(
                name = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                api = "openai-completions",
                hint = "OpenRouter aggregates Claude, GPT, Gemini, MiMo and more — one Key for all.",
                models = listOf(
                    modelPreset("qwen/qwen3.6-plus:free", "🆓 Qwen 3.6 Plus (Default, Free, Best Coding)", reasoning = true, contextWindow = 131072, maxTokens = 32000),
                    modelPreset("openrouter/hunter-alpha", "Hunter Alpha (Free, 1M context)", reasoning = true, contextWindow = 1048576, maxTokens = 65536),
                    modelPreset("openrouter/free", "Free Auto Router (No Top-up Required)"),
                    modelPreset("stepfun/step-3.5-flash:free", "Step 3.5 Flash (Free, Coding)", contextWindow = 262144),
                    modelPreset("anthropic/claude-sonnet-4", "Claude Sonnet 4 (Paid, Recommended)", contextWindow = 200000, maxTokens = 16384),
                    modelPreset("anthropic/claude-opus-4", "Claude Opus 4 (Paid)", contextWindow = 200000, maxTokens = 32768),
                    modelPreset("openai/gpt-4.1", "GPT-4.1 (Paid)", contextWindow = 1048576, maxTokens = 32768),
                    modelPreset("google/gemini-2.5-pro", "Gemini 2.5 Pro (Paid)", contextWindow = 1048576, maxTokens = 65536)
                ),
                authHeader = true
            ),
            "google" to providerPreset(
                name = "Google (Gemini)",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                api = "google-generative-ai",
                hint = "Google Gemini API. Register: aistudio.google.com/apikey",
                models = listOf(
                    modelPreset("gemini-2.5-pro", "Gemini 2.5 Pro (Recommended, Reasoning)", reasoning = true, contextWindow = 1048576, maxTokens = 65536),
                    modelPreset("gemini-2.5-flash", "Gemini 2.5 Flash (Fast, Reasoning)", reasoning = true, contextWindow = 1048576, maxTokens = 65536)
                ),
                authHeader = true
            ),
            "anthropic" to providerPreset(
                name = "Anthropic",
                baseUrl = "https://api.anthropic.com/v1",
                api = "anthropic-messages",
                hint = "Anthropic official API, direct connection to Claude. Register: console.anthropic.com",
                models = listOf(
                    modelPreset("claude-sonnet-4-20250514", "Claude Sonnet 4 (Recommended)"),
                    modelPreset("claude-opus-4-20250514", "Claude Opus 4"),
                    modelPreset("claude-haiku-3-5-20241022", "Claude 3.5 Haiku (Fast)")
                )
            ),
            "xiaomi" to providerPreset(
                name = "Xiaomi MiMo",
                baseUrl = "https://api.xiaomimimo.com/v1",
                api = "openai-completions",
                hint = "Xiaomi MiMo LLM. Register: xiaomimimo.com",
                models = listOf(
                    modelPreset("mimo-v2-pro", "MiMo V2 Pro (1M, Reasoning)", reasoning = true, contextWindow = 1048576, maxTokens = 32000),
                    modelPreset("mimo-v2-flash", "MiMo V2 Flash (262K)", reasoning = false, contextWindow = 262144, maxTokens = 8192),
                    modelPreset("mimo-v2-omni", "MiMo V2 Omni (262K, Reasoning+Vision)", reasoning = true, contextWindow = 262144, maxTokens = 32000)
                ),
                authHeader = true
            ),
            "openai" to providerPreset(
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                api = "openai-completions",
                hint = "OpenAI official API. Register: platform.openai.com",
                models = listOf(
                    modelPreset("gpt-4.1", "GPT-4.1 (Recommended)"),
                    modelPreset("gpt-4.1-mini", "GPT-4.1 Mini (Fast)"),
                    modelPreset("o3", "o3 (Reasoning)")
                )
            ),
            "nvidia" to providerPreset(
                name = "NVIDIA NIM",
                baseUrl = "https://integrate.api.nvidia.com/v1",
                api = "openai-completions",
                hint = "NVIDIA NIM hosted models, free trial. Register: build.nvidia.com",
                models = listOf(
                    modelPreset("moonshotai/kimi-k2.5", "Kimi K2.5 (Free, Multimodal)", contextWindow = 131072, maxTokens = 8192),
                    modelPreset("deepseek-ai/deepseek-r1", "DeepSeek R1 (Free, Reasoning)", reasoning = true, contextWindow = 131072, maxTokens = 8192),
                    modelPreset("meta/llama-4-maverick-17b-128e-instruct", "Llama 4 Maverick 17B", contextWindow = 131072, maxTokens = 8192),
                    modelPreset("", "Enter model ID Manually")
                ),
                authHeader = true
            ),
            "custom" to providerPreset(
                name = "Custom",
                baseUrl = "",
                api = "openai-completions",
                hint = "Supports any OpenAI API-compatible service (vLLM, Ollama, OneAPI, etc.).",
                models = listOf(
                    modelPreset("", "Enter model ID Manually")
                )
            )
        )
    }

    private lateinit var binding: ActivitymodelSetupBinding
    private val configLoader by lazy { configLoader(this) }
    private var selectedprovider = "openrouter"
    private var advancedExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitymodelSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.app {
            setDisplayHomeAsUpEnabled(true)
            title = "model Settings"
        }

        // Apply navigation bar insets to bottom button bar so it won't be obscured
        appNavigationBarInsets()

        setupDefaultMode()
        setupAdvancedToggle()
        setupproviderSelection()
        setupbuttons()
    }

    /**
     * Ensure the bottom button bar respects the system navigation bar height.
     */
    private fun appNavigationBarInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.layoutBottombuttons) { view, windowInsets ->
            val navBarInsets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            view.setPaing(
                view.paingLeft,
                view.paingTop,
                view.paingRight,
                16.dp(this) + navBarInsets.bottom
            )
            windowInsets
        }
    }

    private fun Int.dp(context: android.content.context): Int {
        return (this * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    /**
     * Default mode: quick setup only asks for API key.
     */
    private fun setupDefaultMode() {
        binding.tilmodel.visibility = View.GONE

        // Default to OpenRouter hint
        appproviderPreset("openrouter")

        // "Open openrouter.com" link
        binding.tvOpenOpenrouter.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/keys")))
            } catch (e: exception) {
                Toast.makeText(this, "cannot open browser", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Toggle advanced options (other providers).
     */
    private fun setupAdvancedToggle() {
        binding.tvAdvanced.setOnClickListener {
            advancedExpanded = !advancedExpanded
            binding.layoutAdvanced.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            binding.tvAdvanced.text = if (advancedExpanded) {
                "⚙️ Collapse Advanced Options"
            } else {
                "⚙️ use Other providers (MiMo / Google / Anthropic / OpenAI / Custom)"
            }

            // if collapsing, reset to OpenRouter
            if (!advancedExpanded && selectedprovider != "openrouter") {
                selectedprovider = "openrouter"
                appproviderPreset("openrouter")
            }
        }
    }

    private fun setupproviderSelection() {
        binding.chipGroupprovider.setOnCheckedStateChangeListener { _, checkedIds ->
            val provider = when {
                checkedIds.contains(R.id.chip_openrouter) -> "openrouter"
                checkedIds.contains(R.id.chip_mimo) -> "xiaomi"
                checkedIds.contains(R.id.chip_google) -> "google"
                checkedIds.contains(R.id.chip_anthropic) -> "anthropic"
                checkedIds.contains(R.id.chip_openai) -> "openai"
                checkedIds.contains(R.id.chip_nvidia) -> "nvidia"
                checkedIds.contains(R.id.chip_custom) -> "custom"
                else -> "openrouter"
            }
            selectedprovider = provider
            appproviderPreset(provider)
        }
    }

    private fun appproviderPreset(providerKey: String) {
        val preset = PROVIDERS[providerKey] ?: return

        binding.app {
            // API Key hint
            tilApiKey.hint = when (providerKey) {
                "xiaomi" -> "Xiaomi MiMo API Key"
                "openrouter" -> "OpenRouter API Key"
                "anthropic" -> "Anthropic API Key"
                "openai" -> "OpenAI API Key"
                "google" -> "Gemini API Key"
                "nvidia" -> "NVIDIA API Key"
                else -> "API Key"
            }
            (tilApiKey as? com.google.android.material.textfield.TextInputLayout)?.helperText = when (providerKey) {
                "xiaomi" -> "Register: platform.xiaomimimo.com"
                "openrouter" -> "Starts with sk-or-"
                "anthropic" -> "Starts with sk-ant-"
                "openai" -> "Starts with sk-"
                "google" -> "Get at aistudio.google.com/apikey"
                "nvidia" -> "Starts with nvapi-, get at build.nvidia.com"
                else -> null
            }

            // Base URL
            etSetupApiBase.setText(preset.baseUrl)
            if (providerKey == "custom") {
                tilApiBase.visibility = View.VISIBLE
                etSetupApiBase.isEnabled = true
            } else {
                tilApiBase.visibility = View.GONE
                etSetupApiBase.isEnabled = false
            }

            // provider hint
            tvproviderHint.text = preset.hint
            tvproviderHint.visibility = if (advancedExpanded) View.VISIBLE else View.GONE

            // model selection: hien for built-in providers, only shown for custom provider
            val modelNames = preset.models.map { it.displayName }
            val adapter = ArrayAdapter(this@modelSetupActivity, android.R.layout.simple_dropdown_item_1line, modelNames)
            actmodel.setAdapter(adapter)
            if (modelNames.isnotEmpty()) {
                actmodel.setText(modelNames[0], false)
            }

            tilmodel.visibility = View.VISIBLE
            if (providerKey == "custom") {
                actmodel.inputType = android.text.InputType.TYPE_CLASS_TEXT
                actmodel.threshold = 100
            } else {
                actmodel.inputType = android.text.InputType.TYPE_NULL
                actmodel.threshold = 1
            }
        }
    }

    private fun setupbuttons() {
        binding.btnSkip.setOnClickListener {
            Log.i(TAG, "user skipped model setup, using default config")
            saveDefaultandFinish()
        }

        binding.btnStart.setOnClickListener {
            saveandFinish()
        }
    }

    private fun saveDefaultandFinish() {
        selectedprovider = "openrouter"
        advancedExpanded = false
        appproviderPreset("openrouter")
        binding.etSetupApiKey.setText("")
        saveandFinish()
    }

    private fun saveandFinish() {
        val userInputKey = binding.etSetupApiKey.text?.toString()?.trim()
        val selectedmodelDisplay = binding.actmodel.text?.toString()?.trim()

        // if user provided a key, use it; otherwise use the built-in encrypted key
        val apiKey = if (userInputKey.isNullorEmpty()) {
            val builtInKey = com.xiaomo.androidforclaw.config.BuiltInKeyprovider.getKey()
            if (builtInKey.isNullorEmpty()) {
                binding.tilApiKey.error = "please enter API Key"
                return
            }
            builtInKey
        } else {
            userInputKey
        }
        binding.tilApiKey.error = null

        // for advanced/custom mode
        val apiBase = if (advancedExpanded) {
            binding.etSetupApiBase.text?.toString()?.trim()
        } else {
            null
        }

        if (selectedprovider == "custom" && apiBase.isNullorEmpty()) {
            binding.tilApiBase.error = "please enter API Base URL"
            return
        }

        // Resolve model ID
        val preset = PROVIDERS[selectedprovider] ?: return
        val matchedPreset = if (selectedprovider == "custom") {
            null
        } else {
            preset.models.firstorNull { it.displayName == selectedmodelDisplay }
                ?: preset.models.firstorNull()
        }
        val modelId = if (selectedprovider == "custom") {
            selectedmodelDisplay ?: ""
        } else {
            matchedPreset?.id ?: ""
        }

        if (selectedprovider == "custom" && modelId.isBlank()) {
            binding.tilmodel.error = "please enter model ID"
            return
        }
        binding.tilmodel.error = null

        try {
            val config = configLoader.loadOpenClawconfig()

            val providerName = if (selectedprovider == "custom") "custom" else selectedprovider
            val newprovider = providerconfig(
                baseUrl = apiBase ?: preset.baseUrl,
                apiKey = apiKey,
                api = preset.api,
                models = listOf(
                    modelDefinition(
                        id = modelId,
                        name = selectedmodelDisplay ?: modelId,
                        reasoning = matchedPreset?.reasoning ?: (modelId.contains("o3") || modelId.contains("r1") || modelId.contains("opus")),
                        contextWindow = matchedPreset?.contextWindow ?: 200000,
                        maxTokens = matchedPreset?.maxTokens ?: 16384
                    )
                ),
                authHeader = preset.authHeader
            )

            val existingmodels = config.models ?: modelsconfig()
            // Only keep the currently selected provider — remove stale entries (Issue #9)
            val updatedproviders = mutableMapOf<String, providerconfig>()
            updatedproviders[providerName] = newprovider

            val defaultmodelId = if (selectedprovider == "custom") {
                "custom/$modelId"
            } else if (modelId.startswith("$providerName/")) {
                // modelId already contains provider prefix (e.g. "openrouter/hunter-alpha")
                modelId
            } else {
                "$providerName/$modelId"
            }

            val modelSelection = com.xiaomo.androidforclaw.config.modelSelectionconfig(primary = defaultmodelId)
            val existingagents = config.agents ?: com.xiaomo.androidforclaw.config.agentsconfig()
            val updatedDefaults = existingagents.defaults.copy(model = modelSelection)
            val updatedagents = existingagents.copy(defaults = updatedDefaults)

            val updatedconfig = config.copy(
                models = existingmodels.copy(providers = updatedproviders),
                agents = updatedagents
            )

            val saved = configLoader.saveOpenClawconfig(updatedconfig)
            if (!saved) {
                Toast.makeText(this, "Save failed: cannot write config file, please check storage permissions", Toast.LENGTH_LONG).show()
                return
            }

            Log.i(TAG, "[OK] model config saved: provider=$providerName, model=$modelId")
            markSetupSeen()
            Toast.makeText(this, "[OK] configuration complete!", Toast.LENGTH_SHORT).show()
            finish()

        } catch (e: exception) {
            Log.e(TAG, "Failed to save config", e)
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun markSetupSeen() {
        try {
            val mmkv = com.tencent.mmkv.MMKV.defaultMMKV()
            mmkv.encode("model_setup_completed", true)
        } catch (e: exception) {
            Log.w(TAG, "Failed to mark setup as seen", e)
        }
    }

    private data class providerPreset(
        val name: String,
        val baseUrl: String,
        val api: String,
        val hint: String,
        val models: List<modelPreset>,
        val authHeader: Boolean = true
    )

    private data class modelPreset(
        val id: String,
        val displayName: String,
        val reasoning: Boolean = false,
        val contextWindow: Int = 200000,
        val maxTokens: Int = 16384
    )

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

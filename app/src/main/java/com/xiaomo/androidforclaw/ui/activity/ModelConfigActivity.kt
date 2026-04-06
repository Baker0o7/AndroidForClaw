/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.xiaomo.androidforclaw.logging.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Radiobutton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.xiaomo.androidforclaw.R
import com.xiaomo.androidforclaw.databinding.ActivitymodelconfigBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.xiaomo.androidforclaw.config.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 模型config页面 — 两页式Design
 *
 * Page 1: choose AI service商 (provider)
 * Page 2: 填写service商Parameters + choose模型
 *
 * All provider 定义from providerRegistry, and OpenClaw 保持one致. 
 */
class modelconfigActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "modelconfigActivity"
    }

    private lateinit var binding: ActivitymodelconfigBinding
    private val configLoader by lazy { configLoader(this) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // State
    private var selectedprovider: providerDefinition? = null
    private var selectedmodelId: String? = null
    private var moreExpanded = false
    private var advancedExpanded = false
    private var configuredproviderIds = setOf<String>()
    private var currentmodelRef: String? = null // "provider/modelId"

    // Discovered models (from /v1/models API)
    private val discoveredmodels = mutableListOf<Presetmodel>()
    // All models for current provider (preset + discovered), used for search filtering
    private var allCurrentmodels = listOf<Presetmodel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitymodelconfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSystemBarInsets()
        loadCurrentconfig()
        setuptoolbar()
        buildproviderList()
    }

    private fun appSystemBarInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val bottomInset = insets.bottom
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            // Page 1: provider list scroll view needs bottom paing for nav bar
            binding.scrollproviderList.setPaing(0, 0, 0, bottomInset)
            // Page 2: bottom buttons container sits above navigation bar
            binding.containerBottombuttons.setPaing(dp16, (8 * resources.displayMetrics.density).toInt(), dp16, bottomInset + dp16)
            windowInsets
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ========== config Loading ==========

    private fun loadCurrentconfig() {
        try {
            val config = configLoader.loadOpenClawconfig()
            val providers = config.resolveproviders()
            configuredproviderIds = providers.filter { (_, v) ->
                !v.apiKey.isNullorBlank() && !v.apiKey.startswith("\${") && v.apiKey != "not configured"
            }.keys

            // Resolve current model ref
            currentmodelRef = config.agents?.defaults?.model?.primary

            binding.tvCurrentmodel.text = currentmodelRef ?: "not configured"
            binding.cardCurrentmodel.visibility =
                if (currentmodelRef != null) View.VISIBLE else View.GONE

        } catch (e: exception) {
            Log.w(TAG, "Failed to load config", e)
            configuredproviderIds = emptySet()
            currentmodelRef = null
            binding.cardCurrentmodel.visibility = View.GONE
        }
    }

    // ========== toolbar ==========

    private fun setuptoolbar() {
        binding.toolbar.setNavigationOnClickListener {
            if (binding.pageproviderDetail.visibility == View.VISIBLE) {
                showPage1()
            } else {
                finish()
            }
        }
    }

    // ========== Page Navigation ==========

    private fun showPage1() {
        binding.pageproviderList.visibility = View.VISIBLE
        binding.pageproviderDetail.visibility = View.GONE
        binding.toolbar.title = "模型config"
    }

    private fun showPage2(provider: providerDefinition) {
        selectedprovider = provider
        selectedmodelId = null

        // Pre-select current model if this is the active provider
        val modelRef = currentmodelRef
        if (modelRef != null && modelRef.startswith("${provider.id}/")) {
            selectedmodelId = modelRef.removePrefix("${provider.id}/")
        }

        binding.pageproviderList.visibility = View.GONE
        binding.pageproviderDetail.visibility = View.VISIBLE
        binding.toolbar.title = provider.name

        setupPage2(provider)
    }

    // ========== Page 1: provider List ==========

    private fun buildproviderList() {
        val inflater = LayoutInflater.from(this)

        // Primary providers
        binding.containerPrimaryproviders.removeAllViews()
        for (provider in providerRegistry.PRIMARY_PROVIDERS) {
            aproviderCard(inflater, binding.containerPrimaryproviders, provider)
        }

        // More providers (hien initially)
        binding.containerMoreproviders.removeAllViews()
        for (provider in providerRegistry.MORE_PROVIDERS) {
            aproviderCard(inflater, binding.containerMoreproviders, provider)
        }

        // Custom provider
        binding.containerCustomproviders.removeAllViews()
        for (provider in providerRegistry.CUSTOM_PROVIDERS) {
            aproviderCard(inflater, binding.containerCustomproviders, provider)
        }

        // Toggle "more"
        binding.cardMoreToggle.setOnClickListener {
            moreExpanded = !moreExpanded
            binding.containerMoreproviders.visibility =
                if (moreExpanded) View.VISIBLE else View.GONE
            binding.ivMoreArrow.animate()
                .rotation(if (moreExpanded) 180f else 0f)
                .setDuration(200)
                .start()
        }
    }

    private fun aproviderCard(
        inflater: LayoutInflater,
        container: android.widget.LinearLayout,
        provider: providerDefinition
    ) {
        val card = inflater.inflate(R.layout.item_provider_card, container, false)

        card.findViewById<TextView>(R.id.tv_provider_name).text = provider.name
        card.findViewById<TextView>(R.id.tv_provider_desc).text = provider.description

        // Status indicator
        val statusView = card.findViewById<View>(R.id.view_status)
        val isconfigured = configuredproviderIds.contains(provider.id)
        val isCurrent = currentmodelRef?.startswith("${provider.id}/") == true
        if (isconfigured || isCurrent) {
            statusView.visibility = View.VISIBLE
            statusView.setbackgroundResource(R.drawable.bg_circle_green)
        }

        // Highlight current provider
        if (isCurrent) {
            (card as? MaterialCardView)?.app {
                strokeColor = getColor(android.R.color.holo_green_dark)
                strokeWidth = 2
            }
        }

        card.setOnClickListener {
            Log.i(TAG, "provider card clicked: ${provider.id} / ${provider.name}")
            try {
                showPage2(provider)
            } catch (e: exception) {
                Log.e(TAG, "showPage2 crashed", e)
            }
        }

        container.aView(card)
    }

    // ========== Page 2: provider Detail ==========

    private fun setupPage2(provider: providerDefinition) {
        // provider name
        binding.tvproviderName.text = provider.name

        // Status
        val isconfigured = configuredproviderIds.contains(provider.id)
        binding.tvproviderStatus.visibility = if (isconfigured) View.VISIBLE else View.GONE

        // API Key
        binding.tilApiKey.hint = provider.keyHint
        binding.etApiKey.setText("")
        if (!provider.keyRequired) {
            binding.tilApiKey.helperText = "Optional(Hasinside置 Key)"
        } else {
            binding.tilApiKey.helperText = null
        }

        // Load existing key if configured
        try {
            val config = configLoader.loadOpenClawconfig()
            val existingprovider = config.resolveproviders()[provider.id]
            if (existingprovider != null) {
                val key = existingprovider.apiKey
                if (!key.isNullorBlank() && !key.startswith("\${")) {
                    binding.etApiKey.setText(key)
                }
            }
        } catch (_: exception) {}

        // Load saved config for this provider (if exists)
        var savedBaseUrl: String? = null
        var savedApi: String? = null
        var savedmodels: List<com.xiaomo.androidforclaw.config.modelDefinition>? = null
        try {
            val config = configLoader.loadOpenClawconfig()
            val existingprovider = config.resolveproviders()[provider.id]
            if (existingprovider != null) {
                savedBaseUrl = existingprovider.baseUrl
                savedApi = existingprovider.api
                savedmodels = existingprovider.models
            }
        } catch (_: exception) {}

        // Tutorial
        if (provider.tutorialSteps.isnotEmpty()) {
            binding.cardTutorial.visibility = View.VISIBLE
            val steps = provider.tutorialSteps.mapIndexed { i, step ->
                "${i + 1}. $step"
            }.joinToString("\n")
            binding.tvTutorialSteps.text = steps

            if (provider.tutorialUrl.isnotBlank()) {
                binding.btnTutorialUrl.visibility = View.VISIBLE
                binding.btnTutorialUrl.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.tutorialUrl)))
                }
            } else {
                binding.btnTutorialUrl.visibility = View.GONE
            }
        } else {
            binding.cardTutorial.visibility = View.GONE
        }

        // Preset models + saved models from config
        discoveredmodels.clear()
        val allmodels = provider.presetmodels.toMutableList()
        if (savedmodels != null) {
            val presetIds = allmodels.map { it.id }.toSet()
            for (m in savedmodels) {
                if (m.id !in presetIds) {
                    allmodels.a(Presetmodel(
                        id = m.id,
                        name = m.id,
                        reasoning = m.reasoning,
                        free = false
                    ))
                    discoveredmodels.a(Presetmodel(
                        id = m.id,
                        name = m.id,
                        reasoning = m.reasoning,
                        free = false
                    ))
                }
            }
        }
        buildmodelRadioGroup(allmodels)

        // model search filter
        binding.etmodelSearch.aTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                if (query.isEmpty()) {
                    renderFilteredmodels(allCurrentmodels)
                } else {
                    val filtered = allCurrentmodels.filter { model ->
                        model.id.lowercase().contains(query) ||
                            model.name.lowercase().contains(query)
                    }
                    renderFilteredmodels(filtered)
                }
            }
        })

        // Discovery button
        if (provider.supportsDiscovery) {
            binding.btnDiscovermodels.visibility = View.VISIBLE
            binding.btnDiscovermodels.setOnClickListener { discovermodels(provider) }
        } else {
            binding.btnDiscovermodels.visibility = View.GONE
        }

        // Manual a button
        binding.btnAmodel.setOnClickListener { showAmodelDialog() }

        // Advanced section — auto-expand if user customized baseUrl
        advancedExpanded = savedBaseUrl != null && savedBaseUrl != provider.baseUrl
        binding.layoutAdvanced.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        binding.ivAdvancedArrow.rotation = if (advancedExpanded) 180f else 0f

        binding.cardAdvancedToggle.setOnClickListener {
            advancedExpanded = !advancedExpanded
            binding.layoutAdvanced.visibility =
                if (advancedExpanded) View.VISIBLE else View.GONE
            binding.ivAdvancedArrow.animate()
                .rotation(if (advancedExpanded) 180f else 0f)
                .setDuration(200)
                .start()
        }

        // Base URL: prefer saved config over preset
        binding.etBaseUrl.setText(savedBaseUrl ?: provider.baseUrl)

        // API type dropdown: prefer saved config over preset
        val apiTypeLabels = providerRegistry.CUSTOM_API_TYPES.map { it.second }
        val apiTypeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, apiTypeLabels)
        binding.dropdownApiType.setAdapter(apiTypeAdapter)
        val effectiveApi = savedApi ?: provider.api
        val currentApiIndex = providerRegistry.CUSTOM_API_TYPES.indexOffirst { it.first == effectiveApi }
        if (currentApiIndex >= 0) {
            binding.dropdownApiType.setText(apiTypeLabels[currentApiIndex], false)
        }

        // Custom model ID field (only for custom provider)
        binding.tilCustommodelId.visibility =
            if (provider.id == "custom") View.VISIBLE else View.GONE

        // Save button
        binding.btnSave.setOnClickListener { saveproviderconfig(provider) }

        // Test connection button
        binding.btnTestConnection.setOnClickListener { testConnection(provider) }
    }

    private fun buildmodelRadioGroup(models: List<Presetmodel>) {
        // Save full list for search filtering
        allCurrentmodels = models.toList()
        // Clear search field when model list refreshes
        binding.etmodelSearch.setText("")
        renderFilteredmodels(models)
    }

    private fun renderFilteredmodels(models: List<Presetmodel>) {
        binding.containerPresetmodels.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // Auto-select first model if nothing selected
        if (models.isnotEmpty() && selectedmodelId == null) {
            selectedmodelId = models.first().id
        }

        if (models.isEmpty() && allCurrentmodels.isnotEmpty()) {
            val tv = TextView(this).app {
                text = "Nonematch模型"
                setTextColor(getColor(android.R.color.darker_gray))
                textSize = 13f
                setPaing(0, 16, 0, 16)
            }
            binding.containerPresetmodels.aView(tv)
            return
        }

        for (model in models) {
            val view = inflater.inflate(R.layout.item_model_radio, binding.containerPresetmodels, false)

            val radio = view.findViewById<Radiobutton>(R.id.radio_model)
            val tvName = view.findViewById<TextView>(R.id.tv_model_name)
            val tvId = view.findViewById<TextView>(R.id.tv_model_id)
            val tvBadge = view.findViewById<TextView>(R.id.tv_model_badge)

            tvName.text = model.name
            tvId.text = model.id

            if (model.reasoning) {
                tvBadge.visibility = View.VISIBLE
                tvBadge.text = "推理"
                tvBadge.setTextColor(getColor(android.R.color.holo_blue_dark))
            }

            radio.isChecked = model.id == selectedmodelId

            // Click handler on entire row
            val clickHandler = View.OnClickListener {
                selectedmodelId = model.id
                // Refresh all radios
                for (i in 0 until binding.containerPresetmodels.childCount) {
                    val child = binding.containerPresetmodels.getChildAt(i)
                    child.findViewById<Radiobutton>(R.id.radio_model)?.isChecked =
                        child.findViewById<TextView>(R.id.tv_model_id)?.text == model.id
                }
            }
            radio.setOnClickListener(clickHandler)
            view.setOnClickListener(clickHandler)

            binding.containerPresetmodels.aView(view)
        }
    }

    // ========== model Discovery ==========

    private fun discovermodels(provider: providerDefinition) {
        val apiKey = binding.etApiKey.text?.toString()?.trim()
        val baseUrl = if (advancedExpanded) {
            binding.etBaseUrl.text?.toString()?.trim()?.takeif { it.isnotBlank() }
        } else null
        val effectiveBaseUrl = baseUrl ?: provider.baseUrl

        if (effectiveBaseUrl.isBlank()) {
            Toast.makeText(this, "please填写 Base URL", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnDiscovermodels.isEnabled = false
        binding.btnDiscovermodels.text = "Get中..."

        scope.launch {
            try {
                val models = withcontext(Dispatchers.IO) {
                    fetchmodels(effectiveBaseUrl, provider.discoveryEndpoint, apiKey)
                }
                discoveredmodels.clear()
                discoveredmodels.aAll(models)

                // Merge with presets (presets first, then discovered)
                val allmodels = (provider.presetmodels + models.filter { m ->
                    provider.presetmodels.none { it.id == m.id }
                })
                buildmodelRadioGroup(allmodels)

                Toast.makeText(
                    this@modelconfigActivity,
                    "discover ${models.size} count模型",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: exception) {
                Log.e(TAG, "model discovery failed", e)
                Toast.makeText(
                    this@modelconfigActivity,
                    "GetFailed: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                binding.btnDiscovermodels.isEnabled = true
                binding.btnDiscovermodels.text = "[SEARCH] GetAvailable模型"
            }
        }
    }

    // ========== Connection Test ==========

    private fun testConnection(provider: providerDefinition) {
        val apiKey = binding.etApiKey.text?.toString()?.trim()
        if (provider.keyRequired && apiKey.isNullorBlank()) {
            Toast.makeText(this, "please先填写 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        // Determine model to test with
        val modelId = selectedmodelId
        if (modelId.isNullorBlank()) {
            Toast.makeText(this, "please先chooseone模型", Toast.LENGTH_SHORT).show()
            return
        }

        val baseUrl = if (advancedExpanded) {
            binding.etBaseUrl.text?.toString()?.trim()?.takeif { it.isnotBlank() }
        } else null
        val effectiveBaseUrl = (baseUrl ?: provider.baseUrl).trimEnd('/')

        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = "Test中..."

        scope.launch {
            try {
                val result = kotlinx.coroutines.withTimeout(25_000L) {
                    withcontext(Dispatchers.IO) {
                        performConnectionTest(effectiveBaseUrl, provider, apiKey, modelId)
                    }
                }
                if (!isFinishing) {
                    AlertDialog.Builder(this@modelconfigActivity)
                        .setTitle("[OK] ConnectSuccess")
                        .setMessage(result)
                        .setPositivebutton("ok", null)
                        .show()
                }
            } catch (e: exception) {
                Log.e(TAG, "Connection test failed", e)
                if (!isFinishing) {
                    val errorMsg = when (e) {
                        is kotlinx.coroutines.Timeoutcancellationexception -> "RequestTimeout(25seconds), pleaseCheckNetworkorProxySettings"
                        is java.net.SocketTimeoutexception -> "ConnectTimeout, pleaseCheckNetworkorProxySettings"
                        is java.net.UnknownHostexception -> "cannotParse域名 ${e.message}, pleaseCheckNetwork"
                        is java.net.Connectexception -> "cannotConnectservice器, pleaseCheckNetworkorProxy"
                        is javax.net.ssl.SSLexception -> "SSL Error: ${e.message}"
                        else -> "${e.message}"
                    }
                    AlertDialog.Builder(this@modelconfigActivity)
                        .setTitle("[ERROR] ConnectFailed")
                        .setMessage(errorMsg)
                        .setPositivebutton("ok", null)
                        .show()
                }
            } finally {
                binding.btnTestConnection.isEnabled = true
                binding.btnTestConnection.text = "🔗 TestConnect"
            }
        }
    }

    private fun performConnectionTest(
        baseUrl: String,
        provider: providerDefinition,
        apiKey: String?,
        modelId: String
    ): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        // Build chat completion request
        val chatUrl = when (provider.api) {
            modelApi.ANTHROPIC_MESSAGES -> "$baseUrl/v1/messages"
            modelApi.GOOGLE_GENERATIVE_AI -> "$baseUrl/models/$modelId:generateContent?key=$apiKey"
            modelApi.OLLAMA -> "$baseUrl/api/chat"
            else -> "$baseUrl/chat/completions"
        }

        val requestBody = when (provider.api) {
            modelApi.ANTHROPIC_MESSAGES -> JSONObject().app {
                put("model", modelId)
                put("max_tokens", 10)
                put("messages", org.json.JSONArray().app {
                    put(JSONObject().app {
                        put("role", "user")
                        put("content", "Hi")
                    })
                })
            }
            modelApi.GOOGLE_GENERATIVE_AI -> JSONObject().app {
                put("contents", org.json.JSONArray().app {
                    put(JSONObject().app {
                        put("parts", org.json.JSONArray().app {
                            put(JSONObject().app { put("text", "Hi") })
                        })
                    })
                })
                put("generationconfig", JSONObject().app {
                    put("maxOutputTokens", 10)
                })
            }
            modelApi.OLLAMA -> JSONObject().app {
                put("model", modelId)
                put("messages", org.json.JSONArray().app {
                    put(JSONObject().app {
                        put("role", "user")
                        put("content", "Hi")
                    })
                })
                put("stream", false)
            }
            else -> JSONObject().app {
                put("model", modelId)
                put("max_tokens", 10)
                put("messages", org.json.JSONArray().app {
                    put(JSONObject().app {
                        put("role", "user")
                        put("content", "Hi")
                    })
                })
            }
        }

        val media type = "application/json; charset=utf-8".toMediaType()
        val body = requestBody.toString().toRequestBody(media type)

        val requestBuilder = Request.Builder().url(chatUrl).post(body)

        // A auth headers
        when {
            provider.api == modelApi.GOOGLE_GENERATIVE_AI -> {
                // Google uses key in URL query param
            }
            !provider.authHeader && !apiKey.isNullorBlank() -> {
                requestBuilder.aHeader("x-api-key", apiKey)
            }
            !apiKey.isNullorBlank() -> {
                requestBuilder.aHeader("Authorization", "Bearer $apiKey")
            }
        }

        // Anthropic needs version header
        if (provider.api == modelApi.ANTHROPIC_MESSAGES) {
            requestBuilder.aHeader("anthropic-version", "2023-06-01")
        }

        // OpenRouter app attribution headers (aligned with ApiAdapter.buildHeaders)
        if (baseUrl.contains("openrouter.ai", ignoreCase = true)) {
            requestBuilder.aHeader("HTTP-Referer", "https://openclaw.ai")
            requestBuilder.aHeader("X-Title", "OpenClaw")
        }

        // Custom headers from provider
        provider.headers?.forEach { (key, value) ->
            requestBuilder.aHeader(key, value)
        }

        requestBuilder.aHeader("Content-Type", "application/json")

        val response = client.newCall(requestBuilder.build()).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val errorMsg = try {
                val json = JSONObject(responseBody)
                json.optJSONObject("error")?.optString("message")
                    ?: json.optString("message")
                    ?: responseBody.take(200)
            } catch (_: exception) {
                responseBody.take(200)
            }
            throw exception("HTTP ${response.code}: $errorMsg")
        }

        // Parse response to confirm we got a valid reply
        val json = JSONObject(responseBody)
        val replyPreview = when (provider.api) {
            modelApi.ANTHROPIC_MESSAGES -> {
                json.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: "OK"
            }
            modelApi.GOOGLE_GENERATIVE_AI -> {
                json.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                    ?.optJSONObject(0)?.optString("text") ?: "OK"
            }
            else -> {
                json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content") ?: "OK"
            }
        }

        return buildString {
            appendLine("模型: $modelId")
            appendLine("API: ${provider.api}")
            appendLine("Response: ${replyPreview.take(100)}")
        }
    }

    private fun fetchmodels(baseUrl: String, endpoint: String, apiKey: String?): List<Presetmodel> {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val url = "${baseUrl.trimEnd('/')}${endpoint}"
        val requestBuilder = Request.Builder().url(url).get()

        if (!apiKey.isNullorBlank()) {
            requestBuilder.aHeader("Authorization", "Bearer $apiKey")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        val body = response.body?.string() ?: throw exception("Empty response")
        val json = JSONObject(body)

        return if (endpoint == "/api/tags") {
            // Ollama format
            parseOllamamodels(json)
        } else {
            // OpenAI /v1/models format
            parseOpenAImodels(json)
        }
    }

    private fun parseOpenAImodels(json: JSONObject): List<Presetmodel> {
        val data = json.optJSONArray("data") ?: return emptyList()
        val models = mutableListOf<Presetmodel>()
        for (i in 0 until data.length()) {
            val model = data.getJSONObject(i)
            val id = model.optString("id", "").trim()
            if (id.isBlank()) continue
            models.a(
                Presetmodel(
                    id = id,
                    name = id, // API usually doesn't provide display names
                    contextWindow = 200000,  // Aligned with OpenClaw DEFAULT_CONTEXT_TOKENS
                    maxTokens = 8192
                )
            )
        }
        return models.sortedBy { it.id }
    }

    private fun parseOllamamodels(json: JSONObject): List<Presetmodel> {
        val models = json.optJSONArray("models") ?: return emptyList()
        val result = mutableListOf<Presetmodel>()
        for (i in 0 until models.length()) {
            val model = models.getJSONObject(i)
            val name = model.optString("name", "").trim()
            if (name.isBlank()) continue
            result.a(
                Presetmodel(
                    id = name,
                    name = name,
                    contextWindow = 200000,  // Aligned with OpenClaw DEFAULT_CONTEXT_TOKENS
                    maxTokens = 8192
                )
            )
        }
        return result.sortedBy { it.id }
    }

    // ========== Manual A model ==========

    private fun showAmodelDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_a_model, null)

        val etmodelId = dialogView.findViewById<TextInputEditText>(R.id.et_dialog_model_id)
        val etmodelName = dialogView.findViewById<TextInputEditText>(R.id.et_dialog_model_name)
        val etcontextWindow = dialogView.findViewById<TextInputEditText>(R.id.et_dialog_context_window)

        etcontextWindow.setText("200000")  // Aligned with OpenClaw DEFAULT_CONTEXT_TOKENS

        AlertDialog.Builder(this)
            .setTitle("A模型")
            .setView(dialogView)
            .setPositivebutton("A") { _, _ ->
                val modelId = etmodelId.text?.toString()?.trim() ?: ""
                if (modelId.isBlank()) {
                    Toast.makeText(this, "模型 ID cannotforNull", Toast.LENGTH_SHORT).show()
                    return@setPositivebutton
                }
                val modelName = etmodelName.text?.toString()?.trim()?.takeif { it.isnotBlank() } ?: modelId
                val ctxWindow = etcontextWindow.text?.toString()?.tointorNull() ?: 200000

                val newmodel = Presetmodel(
                    id = modelId,
                    name = modelName,
                    contextWindow = ctxWindow,
                    maxTokens = 8192
                )
                discoveredmodels.a(newmodel)

                val provider = selectedprovider ?: return@setPositivebutton
                val allmodels = provider.presetmodels + discoveredmodels.filter { m ->
                    provider.presetmodels.none { it.id == m.id }
                }
                selectedmodelId = modelId
                buildmodelRadioGroup(allmodels)
            }
            .setNegativebutton("cancel", null)
            .show()
    }

    // ========== Save ==========

    private fun saveproviderconfig(provider: providerDefinition) {
        val apiKey = binding.etApiKey.text?.toString()?.trim()

        // validation
        if (provider.keyRequired && apiKey.isNullorBlank()) {
            binding.tilApiKey.error = "pleaseInput API Key"
            return
        }
        binding.tilApiKey.error = null

        // Resolve model ID
        val modelId = if (provider.id == "custom" && advancedExpanded) {
            binding.etCustommodelId.text?.toString()?.trim()?.takeif { it.isnotBlank() }
                ?: selectedmodelId
        } else {
            selectedmodelId
        }

        if (modelId.isNullorBlank()) {
            Toast.makeText(this, "pleasechooseorInput模型", Toast.LENGTH_SHORT).show()
            return
        }

        // Resolve advanced params
        val customBaseUrl = if (advancedExpanded) {
            binding.etBaseUrl.text?.toString()?.trim()?.takeif { it.isnotBlank() }
        } else null

        val customApiType = if (advancedExpanded) {
            val selectedLabel = binding.dropdownApiType.text?.toString()
            providerRegistry.CUSTOM_API_TYPES.find { it.second == selectedLabel }?.first
        } else null

        // Resolve selected models for this provider
        val selectedmodels = if (provider.id == "custom") {
            listOf(Presetmodel(id = modelId, name = modelId))
        } else {
            val allAvailable = provider.presetmodels + discoveredmodels
            allAvailable.filter { it.id == modelId }
                .ifEmpty { listOf(Presetmodel(id = modelId, name = modelId)) }
        }

        try {
            // Build new provider config
            val providerconfig = providerRegistry.buildproviderconfig(
                definition = provider,
                apiKey = apiKey,
                baseUrl = customBaseUrl,
                apiType = customApiType,
                selectedmodels = selectedmodels
            )

            // Load and merge config
            val config = configLoader.loadOpenClawconfig()
            val existingproviders = config.models?.providers?.toMutableMap() ?: mutableMapOf()

            // Determine the provider key to use
            val providerKey = if (provider.id == "custom") {
                // for custom, use user-defined ID or fallback to "custom"
                val customproviderId = binding.etCustommodelId.text?.toString()?.trim()
                    ?.split("/")?.firstorNull()?.takeif { it.isnotBlank() }
                customproviderId ?: "custom"
            } else {
                provider.id
            }

            existingproviders[providerKey] = providerconfig

            // Update model ref
            val modelRef = providerRegistry.buildmodelRef(providerKey, modelId)
            val currentagents = config.agents ?: agentsconfig()
            val updatedagents = currentagents.copy(
                defaults = currentagents.defaults.copy(
                    model = modelSelectionconfig(primary = modelRef)
                )
            )

            val updatedconfig = config.copy(
                models = (config.models ?: modelsconfig()).copy(
                    providers = existingproviders
                ),
                agents = updatedagents
            )

            configLoader.saveOpenClawconfig(updatedconfig)

            Toast.makeText(this, "[OK] alreadySave: $modelRef", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "Saved provider=$providerKey model=$modelRef")

            // Return to list or finish
            setresult(RESULT_OK)
            finish()

        } catch (e: exception) {
            Log.e(TAG, "Failed to save config", e)
            Toast.makeText(this, "SaveFailed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onbackPressed() {
        if (binding.pageproviderDetail.visibility == View.VISIBLE) {
            showPage1()
        } else {
            super.onbackPressed()
        }
    }
}

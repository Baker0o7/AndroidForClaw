package com.xiaomo.androidforclaw.ui

import android.content.Intent
import android.view.View
import android.widget.AutoCompleteTextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import com.xiaomo.androidforclaw.R
import com.xiaomo.androidforclaw.config.ConfigLoader
import com.xiaomo.androidforclaw.ui.activity.ModelSetupActivity
import org.hamcrest.Matchers.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ModelSetupActivity UI Auto化Test
 *
 * Override场景: 
 * 1. 页面Start & 基本Element展示
 * 2. DefaultSchema交互(OpenRouter)
 * 3. 高级Options展开/收起
 * 4. Provider 切换
 * 5. 不填 Key 直接Start(Inside置 Key)
 * 6. 填入Custom Key Start
 * 7. Skip按钮
 * 8. Custom Provider 特殊 UI
 * 9. 模型选择Down拉
 * 10. ErrorHintValidate
 *
 * Run:
 * adb shell am instrument -w -e class com.xiaomo.androidforclaw.ui.ModelSetupActivityUITest \
 *   com.xiaomo.androidforclaw.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(maxSdkVersion = 35) // Espresso InputManager.getInstance() removed in API 36
class ModelSetupActivityUITest {

    private var scenario: ActivityScenario<ModelSetupActivity>? = null

    private fun launchActivity(manual: Boolean = false): ActivityScenario<ModelSetupActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ModelSetupActivity::class.java).apply {
            if (manual) putExtra(ModelSetupActivity.EXTRA_MANUAL, true)
        }
        return ActivityScenario.launch<ModelSetupActivity>(intent).also { scenario = it }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun expandAdvanced() {
        onView(withId(R.id.tv_advanced)).perform(scrollTo(), click())
    }

    private fun selectProvider(chipId: Int) {
        scenario!!.onActivity { activity ->
            activity.findViewById<com.google.android.material.chip.Chip>(chipId).performClick()
        }
    }

    private fun setApiKeyDirect(text: String) {
        scenario!!.onActivity { activity ->
            activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_setup_api_key)
                .setText(text)
        }
    }

    private fun setApiBaseDirect(text: String) {
        scenario!!.onActivity { activity ->
            activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_setup_api_base)
                .setText(text)
        }
    }

    private fun setModelDirect(text: String) {
        scenario!!.onActivity { activity ->
            activity.findViewById<AutoCompleteTextView>(R.id.act_model)
                .setText(text, false)
        }
    }

    // ==================== 1. 页面Start & 基本Element ====================

    @Test
    fun test01_activityLaunches() {
        launchActivity()
        onView(withText("欢迎使用 AndroidForClaw"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun test02_welcomeElementsDisplayed() {
        launchActivity()
        onView(withText("🤖")).check(matches(isDisplayed()))
        onView(withText("欢迎使用 AndroidForClaw")).check(matches(isDisplayed()))
        onView(withText("已Inside置 Key, 可直接Start使用")).check(matches(isDisplayed()))
    }

    @Test
    fun test03_tutorialCardDisplayed() {
        launchActivity()
        onView(withText("📖 如何Get API Key?")).check(matches(isDisplayed()))
    }

    @Test
    fun test04_apiKeyInputDisplayed() {
        launchActivity()
        onView(withId(R.id.et_setup_api_key)).check(matches(isDisplayed()))
    }

    @Test
    fun test05_modelDropdownVisibleInDefaultQuickSetup() {
        launchActivity()
        onView(withId(R.id.til_model))
            .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    @Test
    fun test06_buttonsDisplayed() {
        launchActivity()
        onView(withId(R.id.btn_skip)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_skip)).check(matches(withText("Skip")))
        onView(withId(R.id.btn_start)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_start)).check(matches(withText("Start使用")))
    }

    @Test
    fun test07_openRouterLinkDisplayed() {
        launchActivity()
        onView(withId(R.id.tv_open_openrouter))
            .check(matches(isDisplayed()))
            .check(matches(withText("🔗 Open openrouter.ai/keys")))
    }

    // ==================== 2. DefaultSchema ====================

    @Test
    fun test08_defaultQuickSetupShowsModelDropdown() {
        launchActivity()
        onView(withId(R.id.til_model))
            .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    @Test
    fun test09_advancedOptionsHiddenByDefault() {
        launchActivity()
        onView(withId(R.id.layout_advanced))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    // ==================== 3. 高级Options展开/收起 ====================

    @Test
    fun test10_advancedToggleExpands() {
        launchActivity()
        expandAdvanced()
        onView(withId(R.id.layout_advanced)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.chip_group_provider)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    @Test
    fun test11_advancedToggleCollapses() {
        launchActivity()
        expandAdvanced()
        onView(withId(R.id.layout_advanced)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))

        onView(withId(R.id.tv_advanced)).perform(scrollTo(), click())
        onView(withId(R.id.layout_advanced))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun test12_advancedToggleTextChanges() {
        launchActivity()
        onView(withId(R.id.tv_advanced))
            .check(matches(withText(containsString("使用Its他Service商"))))

        onView(withId(R.id.tv_advanced)).perform(scrollTo(), click())
        onView(withId(R.id.tv_advanced))
            .check(matches(withText(containsString("收起"))))

        onView(withId(R.id.tv_advanced)).perform(scrollTo(), click())
        onView(withId(R.id.tv_advanced))
            .check(matches(withText(containsString("使用Its他Service商"))))
    }

    // ==================== 4. Provider 切换 ====================

    @Test
    fun test13_openrouterChipSelectedByDefault() {
        launchActivity()
        expandAdvanced()
        onView(withId(R.id.chip_openrouter)).check(matches(isChecked()))
    }

    @Test
    fun test14_switchToAnthropic() {
        launchActivity()
        expandAdvanced()
        selectProvider(R.id.chip_anthropic)

        // Verify hint changes via the EditText's hint
        onView(withId(R.id.et_setup_api_key))
            .check(matches(withHint("Anthropic API Key")))

        onView(withId(R.id.til_model))
            .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    @Test
    fun test15_switchToOpenAI() {
        launchActivity()
        expandAdvanced()
        selectProvider(R.id.chip_openai)

        onView(withId(R.id.et_setup_api_key))
            .check(matches(withHint("OpenAI API Key")))

        onView(withId(R.id.til_model))
            .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    @Test
    fun test16_switchToCustom() {
        launchActivity()
        expandAdvanced()
        selectProvider(R.id.chip_custom)

        scenario!!.onActivity { activity ->
            val tilApiBase = activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_api_base)
            val etApiBase = activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_setup_api_base)
            assert(tilApiBase.visibility == android.view.View.VISIBLE) { "API Base field should be visible in custom mode" }
            assert(etApiBase.isEnabledd) { "API Base input should be enabled in custom mode" }
        }
    }

    @Test
    fun test17_collapseAdvancedResetsToOpenRouter() {
        launchActivity()
        expandAdvanced()
        selectProvider(R.id.chip_anthropic)

        // Collapse → reset
        onView(withId(R.id.tv_advanced)).perform(scrollTo(), click())

        // Expand again → should be back to OpenRouter
        onView(withId(R.id.tv_advanced)).perform(scrollTo(), click())
        onView(withId(R.id.chip_openrouter)).check(matches(isChecked()))
        onView(withId(R.id.til_model))
            .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    // ==================== 5. 不填 Key 直接Start(Inside置 Key)====================

    @Test
    fun test18_startWithoutKey_usesBuiltIn() {
        val s = launchActivity(manual = true)
        onView(withId(R.id.btn_start)).perform(click())
        Thread.sleep(1000)
        // Activity should finish if built-in key is available
        assertEquals(Lifecycle.State.DESTROYED, s.state)
    }

    // ==================== 6. 填入Custom Key ====================

    @Test
    fun test19_enterCustomKey() {
        val s = launchActivity(manual = true)
        setApiKeyDirect("sk-or-v1-test123456")
        onView(withId(R.id.et_setup_api_key))
            .check(matches(withText("sk-or-v1-test123456")))
        onView(withId(R.id.btn_start)).perform(click())
        Thread.sleep(1000)
        assertEquals(Lifecycle.State.DESTROYED, s.state)
    }

    // ==================== 7. Skip按钮 ====================

    @Test
    fun test19b_startWithoutKey_persistsOpenRouterProviderConfig() {
        val s = launchActivity(manual = true)
        onView(withId(R.id.btn_start)).perform(click())
        Thread.sleep(1000)
        assertEquals(Lifecycle.State.DESTROYED, s.state)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configLoader = ConfigLoader(context)
        val config = configLoader.loadOpenClawConfig()

        val defaultModel = config.resolveDefaultModel()
        assert(defaultModel.startsWith("openrouter/")) {
            "Expected default model to use openrouter, got: $defaultModel"
        }

        val openrouter = config.resolveProviders()["openrouter"]
        assert(openrouter != null) { "Expected openrouter provider to be persisted in config" }
        assert(openrouter!!.baseUrl.contains("openrouter.ai")) {
            "Expected OpenRouter baseUrl, got: ${openrouter.baseUrl}"
        }
        assert(openrouter.api == "openai-completions") {
            "Expected openai-completions api, got: ${openrouter.api}"
        }
        assert(openrouter.models.any { it.id == defaultModel }) {
            "Expected openrouter provider to contain default model: $defaultModel"
        }
    }

    @Test
    fun test19c_startWithoutKey_defaultModelCanResolveToProvider() {
        val s = launchActivity(manual = true)
        onView(withId(R.id.btn_start)).perform(click())
        Thread.sleep(1000)
        assertEquals(Lifecycle.State.DESTROYED, s.state)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configLoader = ConfigLoader(context)
        val defaultModel = configLoader.loadOpenClawConfig().resolveDefaultModel()
        val providerName = defaultModel.substringBefore('/')
        val modelId = defaultModel.substringAfter('/', "")

        val provider = configLoader.getProviderConfig(providerName)
        assert(provider != null) {
            "Expected provider '$providerName' to exist for default model '$defaultModel'"
        }
        assert(provider!!.models.any { it.id == defaultModel || it.id == modelId }) {
            "Expected provider '$providerName' to contain model '$defaultModel' or '$modelId'"
        }

        val resolvedProvider = configLoader.findProviderByModelId(defaultModel)
        assert(resolvedProvider == providerName) {
            "Expected config loader to resolve provider '$providerName' for '$defaultModel', got '$resolvedProvider'"
        }
    }


    @Test
    fun test19d_resolveProviders_fallsBackToLegacyProvidersWhenModelsProvidersEmpty() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacyProvider = com.xiaomo.androidforclaw.config.ProviderConfig(
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "test-key",
            api = "openai-completions",
            models = listOf(
                com.xiaomo.androidforclaw.config.ModelDefinition(
                    id = "openrouter/hunter-alpha",
                    name = "Hunter Alpha"
                )
            )
        )

        val config = com.xiaomo.androidforclaw.config.OpenClawConfig(
            models = com.xiaomo.androidforclaw.config.ModelsConfig(providers = emptyMap()),
            providers = mapOf("openrouter" to legacyProvider)
        )

        val resolved = config.resolveProviders()
        assert(resolved.containsKey("openrouter")) {
            "Expected resolveProviders() to fall back to top-level providers when models.providers is empty"
        }
    }


    @Test
    fun test20_skipButton_usesDefaultConfiguration() {
        val s = launchActivity()
        onView(withId(R.id.btn_skip)).perform(click())
        Thread.sleep(1000)
        assertEquals(Lifecycle.State.DESTROYED, s.state)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configLoader = ConfigLoader(context)
        val config = configLoader.loadOpenClawConfig()
        val defaultModel = config.resolveDefaultModel()
        val openrouter = config.resolveProviders()["openrouter"]

        assert(defaultModel.startsWith("openrouter/")) {
            "Expected skip to use default openrouter model, got: $defaultModel"
        }
        assert(openrouter != null) { "Expected skip to persist default openrouter provider" }
    }

    // ==================== 8. Custom Provider 特殊 UI ====================

    @Test
    fun test21_customProvider_baseUrlRequired() {
        launchActivity()
        expandAdvanced()
        selectProvider(R.id.chip_custom)

        // Enter key but no base URL
        setApiKeyDirect("sk-test")
        onView(withId(R.id.btn_start)).perform(click())

        Thread.sleep(500)

        // Should show error — verify activity is NOT finished (validation failed)
        scenario!!.onActivity { activity ->
            assert(!activity.isFinishing) { "Activity should not finish without base URL" }
        }
    }

    @Test
    fun test22_customProvider_modelInputIsEditable() {
        launchActivity()
        expandAdvanced()
        selectProvider(R.id.chip_custom)

        // Model input should be freely editable
        setModelDirect("my-custom-model")
        onView(withId(R.id.act_model))
            .check(matches(withText("my-custom-model")))
    }

    @Test
    fun test23_customProvider_fillAllFields() {
        val s = launchActivity(manual = true)
        expandAdvanced()
        selectProvider(R.id.chip_custom)

        setApiKeyDirect("sk-my-key-123")
        setApiBaseDirect("http://localhost:8080/v1")
        setModelDirect("qwen2.5:7b")

        onView(withId(R.id.btn_start)).perform(click())
        Thread.sleep(1000)
        assertEquals(Lifecycle.State.DESTROYED, s.state)
    }

    // ==================== 9. 模型Input(仅Custom Provider) ====================

    @Test
    fun test24_customProvider_modelInputBecomesVisible() {
        launchActivity()
        expandAdvanced()
        selectProvider(R.id.chip_custom)
        onView(withId(R.id.til_model)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    @Test
    fun test25_customProvider_modelInputIsEditable() {
        launchActivity()
        expandAdvanced()
        selectProvider(R.id.chip_custom)
        setModelDirect("my-custom-model")
        onView(withId(R.id.act_model))
            .check(matches(withText("my-custom-model")))
    }

    // ==================== 10. Provider hint Validate ====================

    @Test
    fun test27_providerHintShowsOnAdvanced() {
        launchActivity()
        expandAdvanced()
        selectProvider(R.id.chip_anthropic)

        onView(withId(R.id.tv_provider_hint))
            .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
            .check(matches(withText(containsString("Anthropic"))))
    }

    @Test
    fun test28_providerHintChangesOnSwitch() {
        launchActivity()
        expandAdvanced()

        selectProvider(R.id.chip_anthropic)
        onView(withId(R.id.tv_provider_hint))
            .check(matches(withText(containsString("Anthropic"))))

        selectProvider(R.id.chip_openai)
        onView(withId(R.id.tv_provider_hint))
            .check(matches(withText(containsString("OpenAI"))))

        selectProvider(R.id.chip_custom)
        onView(withId(R.id.tv_provider_hint))
            .check(matches(withText(containsString("兼容"))))
    }

    // ==================== 11. API Key InputBehavior ====================

    @Test
    fun test29_apiKeyInputIsPlainText() {
        launchActivity()
        setApiKeyDirect("visible-key")
        onView(withId(R.id.et_setup_api_key))
            .check(matches(withText("visible-key")))
    }

    @Test
    fun test30_apiKeyHelperText() {
        launchActivity()
        // The helper text "Optional, 留Null则使用Inside置 Key" is in a child of TextInputLayout
        onView(withText(containsString("Optional")))
            .check(matches(isDisplayed()))
    }

    // ==================== 12. 教程链接 ====================

    @Test
    fun test31_tutorialStepsDisplayed() {
        launchActivity()
        onView(withText(containsString("Open openrouter.ai RegisterAccount")))
            .check(matches(isDisplayed()))
        onView(withText(containsString("Copy Key")))
            .check(matches(isDisplayed()))
    }

    @Test
    fun test32_descriptionTextDisplayed() {
        launchActivity()
        onView(withText(containsString("OpenRouter Aggregate了")))
            .check(matches(isDisplayed()))
    }

    @Test
    fun test33_advancedToggleText() {
        launchActivity()
        onView(withId(R.id.tv_advanced))
            .check(matches(withText(containsString("Anthropic"))))
    }
}

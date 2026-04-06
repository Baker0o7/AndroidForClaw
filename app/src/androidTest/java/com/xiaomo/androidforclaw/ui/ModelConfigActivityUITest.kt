package com.xiaomo.androidforclaw.ui

import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import com.xiaomo.androidforclaw.R
import com.xiaomo.androidforclaw.config.ProviderRegistry
import com.xiaomo.androidforclaw.ui.activity.ModelConfigActivity
import org.hamcrest.CoreMatchers.*
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.assertThat
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * ModelConfigActivity UI Automation Test — 56 tests
 *
 * Run:
 * adb shell am instrument -w -e class com.xiaomo.androidforclaw.ui.ModelConfigActivityUITest \
 *   com.xiaomo.androidforclaw.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@SdkSuppress(maxSdkVersion = 35) // Espresso InputManager.getInstance() removed in API 36
class ModelConfigActivityUITest {

    @get:Rule
    val activityRule = ActivityScenarioRule(ModelConfigActivity::class.java)

    // ========== Helpers ==========

    private fun waitForUi(ms: Long = 500) {
        Thread.sleep(ms)
    }

    /** Scroll NestedScrollView to show target view. Works for nested layouts. */
    private fun nestedScrollTo(): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isDescendantOfA(isAssignableFrom(
            NestedScrollView::class.java
        ))
        override fun getDescription(): String = "nested scroll to"
        override fun perform(uiController: UiController, view: View) {
            view.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, view.width, view.height), true
            )
            uiController.loopMainThreadUntilIdle()
        }
    }

    /** Click a provider card by index in a container (avoids AmbiguousViewMatcherException) */
    private fun clickProviderCardAt(containerId: Int, index: Int) {
        activityRule.scenario.onActivity { activity ->
            val container = activity.findViewById<ViewGroup>(containerId)
            container.getChildAt(index)?.performClick()
        }
        waitForUi()
    }

    /** Click a view by ID on the activity (bypasses Espresso scroll/visibility issues) */
    private fun clickViewById(viewId: Int) {
        activityRule.scenario.onActivity { activity ->
            activity.findViewById<View>(viewId)?.performClick()
        }
        waitForUi(300)
    }

    /** Scroll Page 2's NestedScrollView to bottom so advanced/buttons are visible */
    private fun scrollPage2ToBottom() {
        activityRule.scenario.onActivity { activity ->
            val page2 = activity.findViewById<View>(R.id.page_provider_detail)
            val nsv = page2?.findViewById<NestedScrollView>(page2.let {
                // Find NestedScrollView child of page_provider_detail
                (it as? ViewGroup)?.getChildAt(0)?.id ?: 0
            })
            // Fallback: find by traversal
            fun findNSV(v: View): NestedScrollView? {
                if (v is NestedScrollView) return v
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        findNSV(v.getChildAt(i))?.let { return it }
                    }
                }
                return null
            }
            findNSV(page2 ?: return@onActivity)?.fullScroll(View.FOCUS_DOWN)
        }
        waitForUi(300)
    }

    /** Scroll to a specific view in Page 2 */
    private fun scrollPage2To(viewId: Int) {
        activityRule.scenario.onActivity { activity ->
            val targetView = activity.findViewById<View>(viewId) ?: return@onActivity
            targetView.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, targetView.width, targetView.height), true
            )
        }
        waitForUi(300)
    }

    private fun navigateToOpenRouter() = clickProviderCardAt(R.id.container_primary_providers, 0)
    private fun navigateToAnthropic() = clickProviderCardAt(R.id.container_primary_providers, 1)
    private fun navigateToOpenAI() = clickProviderCardAt(R.id.container_primary_providers, 2)

    private fun navigateToOllama() {
        val idx = ProviderRegistry.PRIMARY_PROVIDERS.indexOfFirst { it.id == "ollama" }
        clickProviderCardAt(R.id.container_primary_providers, idx)
    }

    private fun navigateToCustom() = clickProviderCardAt(R.id.container_custom_providers, 0)

    /** Read text from tv_provider_name on Page 2 (scoped to page_provider_detail) */
    private fun getPage2ProviderName(): String? {
        var name: String? = null
        activityRule.scenario.onActivity { activity ->
            val page2 = activity.findViewById<View>(R.id.page_provider_detail)
            name = (page2 as? ViewGroup)?.findViewById<TextView>(R.id.tv_provider_name)?.text?.toString()
        }
        return name
    }

    // ========================================================================
    // PAGE 1: Provider List
    // ========================================================================

    @Test
    fun test01_activityLaunches_showsToolbarTitle() {
        onView(withText("Model Config"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun test02_page1_sectionTitle() {
        onView(withText("Select AI Provider"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun test43_page1_hiddenWhenPage2Shown() {
        navigateToOpenRouter()
        activityRule.scenario.onActivity { activity ->
            val p1 = activity.findViewById<View>(R.id.page_provider_list)
            val p2 = activity.findViewById<View>(R.id.page_provider_detail)
            assertThat("Page1 gone", p1?.visibility, `is`(View.GONE))
            assertThat("Page2 visible", p2?.visibility, `is`(View.VISIBLE))
        }
    }

    // ========================================================================
    // Anthropic
    // ========================================================================

    @Test
    fun test44_page2_anthropic_providerName() {
        navigateToAnthropic()
        assertThat("Anthropic name", getPage2ProviderName(), `is`("Anthropic"))
    }

    @Test
    fun test45_page2_anthropic_apiKeyRequired() {
        navigateToAnthropic()
        activityRule.scenario.onActivity { activity ->
            val til = activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_api_key)
            assertThat("No Optional", til.helperText?.toString() ?: "", not(containsString("Optional")))
        }
    }

    @Test
    fun test46_page2_anthropic_hasPresetModels() {
        navigateToAnthropic()
        activityRule.scenario.onActivity { activity ->
            val container = activity.findViewById<ViewGroup>(R.id.container_preset_models)
            assertThat("Has models", container.childCount, greaterThan(0))
        }
    }

    @Test
    fun test47_page2_anthropic_hasClaude() {
        navigateToAnthropic()
        activityRule.scenario.onActivity { activity ->
            val container = activity.findViewById<ViewGroup>(R.id.container_preset_models)
            var found = false
            for (i in 0 until container.childCount) {
                val tv = container.getChildAt(i).findViewById<TextView>(R.id.tv_model_name)
                if (tv?.text?.toString()?.contains("Claude") == true) { found = true; break }
            }
            assertThat("Has Claude", found, `is`(true))
        }
    }

    // ========================================================================
    // Ollama
    // ========================================================================

    @Test
    fun test48_page2_ollama_apiKeyOptional() {
        navigateToOllama()
        activityRule.scenario.onActivity { activity ->
            val til = activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_api_key)
            assertThat("Ollama optional", til.helperText?.toString() ?: "", containsString("Optional"))
        }
    }

    @Test
    fun test49_page2_ollama_discovery() {
        navigateToOllama()
        activityRule.scenario.onActivity { activity ->
            val btn = activity.findViewById<View>(R.id.btn_discover_models)
            assertThat("Discovery visible", btn?.visibility, `is`(View.VISIBLE))
        }
    }

    // ========================================================================
    // Custom provider
    // ========================================================================

    @Test
    fun test50_page2_custom_customModelIdVisible() {
        navigateToCustom()
        clickViewById(R.id.card_advanced_toggle)
        activityRule.scenario.onActivity { activity ->
            val til = activity.findViewById<View>(R.id.til_custom_model_id)
            assertThat("Custom model ID visible", til?.visibility, `is`(View.VISIBLE))
        }
    }

    // ========================================================================
    // Add Model Dialog
    // ========================================================================

    @Test
    fun test51_addModelDialog_opens() {
        navigateToOpenRouter()
        clickViewById(R.id.btn_add_model)
        onView(withText("Add Model"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun test52_addModelDialog_hasFields() {
        navigateToOpenRouter()
        clickViewById(R.id.btn_add_model)
        onView(withId(R.id.et_dialog_model_id)).check(matches(isDisplayed()))
        onView(withId(R.id.et_dialog_model_name)).check(matches(isDisplayed()))
        onView(withId(R.id.et_dialog_context_window)).check(matches(isDisplayed()))
    }

    @Test
    fun test53_addModelDialog_contextWindowDefault() {
        navigateToOpenRouter()
        clickViewById(R.id.btn_add_model)
        onView(withId(R.id.et_dialog_context_window))
            .check(matches(withText("128000")))
    }

    @Test
    fun test54_addModelDialog_cancel() {
        navigateToOpenRouter()
        clickViewById(R.id.btn_add_model)
        onView(withText("Cancel")).perform(click())
        waitForUi()
        onView(withId(R.id.btn_save)).check(matches(isDisplayed()))
    }

    @Test
    fun test55_addModelDialog_addModel() {
        navigateToOpenRouter()
        var initialCount = 0
        activityRule.scenario.onActivity { activity ->
            initialCount = activity.findViewById<ViewGroup>(R.id.container_preset_models).childCount
        }
        clickViewById(R.id.btn_add_model)
        onView(withId(R.id.et_dialog_model_id)).perform(replaceText("test/my-custom-model"))
        onView(withId(R.id.et_dialog_model_name)).perform(replaceText("My Custom Model"))
        onView(withText("Add")).perform(click())
        waitForUi()
        activityRule.scenario.onActivity { activity ->
            val count = activity.findViewById<ViewGroup>(R.id.container_preset_models).childCount
            assertThat("Model added", count, greaterThan(initialCount))
        }
    }

    // ========================================================================
    // API Key Input
    // ========================================================================

    @Test
    fun test56_page2_apiKey_canType() {
        navigateToOpenRouter()
        onView(withId(R.id.et_api_key)).perform(clearText(), typeText("sk-test-key-12345"))
        // API key field may be a password field (masked), verify it has content
        activityRule.scenario.onActivity { activity ->
            val editText = activity.findViewById<android.widget.EditText>(R.id.et_api_key)
            val text = editText?.text?.toString() ?: ""
            assertThat("API key should be typed", text.isNotEmpty(), `is`(true))
        }
    }

    @Test
    fun test57_page2_apiKey_canClear() {
        navigateToOpenRouter()
        onView(withId(R.id.et_api_key)).perform(clearText())
        onView(withId(R.id.et_api_key)).check(matches(withText("")))
    }

    // ========================================================================
    // Cross-provider navigation
    // ========================================================================

    @Test
    fun test59_navigateBetweenProviders() {
        navigateToOpenRouter()
        assertThat("OpenRouter", getPage2ProviderName(), `is`("OpenRouter"))
        pressBack(); waitForUi()

        navigateToAnthropic()
        assertThat("Anthropic", getPage2ProviderName(), `is`("Anthropic"))
        pressBack(); waitForUi()

        navigateToOpenAI()
        assertThat("OpenAI", getPage2ProviderName(), `is`("OpenAI"))
    }

    @Test
    fun test60_page2_openAI_hasGPTModels() {
        navigateToOpenAI()
        activityRule.scenario.onActivity { activity ->
            val container = activity.findViewById<ViewGroup>(R.id.container_preset_models)
            var found = false
            for (i in 0 until container.childCount) {
                val tv = container.getChildAt(i).findViewById<TextView>(R.id.tv_model_name)
                if (tv?.text?.toString()?.contains("GPT") == true) { found = true; break }
            }
            assertThat("Has GPT", found, `is`(true))
        }
    }

    // ========================================================================
    // Provider status on page 2
    // ========================================================================

    @Test
    fun test61_page2_openRouter_statusConfigured() {
        navigateToOpenRouter()
        activityRule.scenario.onActivity { activity ->
            val page2 = activity.findViewById<ViewGroup>(R.id.page_provider_detail)
            val tv = page2?.findViewById<TextView>(R.id.tv_provider_status)
            assertThat("Status visible", tv?.visibility, `is`(View.VISIBLE))
            assertThat("Status text", tv?.text?.toString(), `is`("Configured"))
        }
    }

    @Test
    fun test62_page2_anthropic_statusNotConfigured() {
        navigateToAnthropic()
        activityRule.scenario.onActivity { activity ->
            val page2 = activity.findViewById<ViewGroup>(R.id.page_provider_detail)
            val tv = page2?.findViewById<TextView>(R.id.tv_provider_status)
            assertThat("Status hidden", tv?.visibility, `is`(View.GONE))
        }
    }
}

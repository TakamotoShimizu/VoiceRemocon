package com.tak.voiceremocon

import android.view.View
import android.widget.Button
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager2.widget.ViewPager2
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenericControlsLegacyHostTest {
  init {
    GenericControlsComposeFlag.setEnabledForTesting(false)
  }

  @get:Rule
  val activityRule = ActivityScenarioRule(VoiceRemocon::class.java)

  @After
  fun resetFeatureFlag() {
    GenericControlsComposeFlag.setEnabledForTesting(false)
  }

  @Test
  fun genericControlsFlagOffInflatesOnlyLegacyViews() {
    activityRule.scenario.onActivity { activity ->
      activity.findViewById<ViewPager2>(R.id.view_pager).currentItem = 1
    }

    var attachedFragment: GenericControlsFragment? = null
    var legacyViewFound = false
    var attempts = 0
    while (!legacyViewFound && attempts < 100) {
      activityRule.scenario.onActivity { activity ->
        attachedFragment = activity.supportFragmentManager.fragments
          .filterIsInstance<GenericControlsFragment>()
          .firstOrNull()
        legacyViewFound =
          attachedFragment?.view?.findViewById<Button>(R.id.record_button) != null
      }
      if (!legacyViewFound) Thread.sleep(50)
      attempts++
    }

    assertNotNull("Generic controls fragment should be attached", attachedFragment)
    assertTrue("Legacy record button should be inflated", legacyViewFound)
    assertTrue(
      "The legacy layout root should not be a ComposeView",
      attachedFragment?.view !is ComposeView
    )
  }
}

@RunWith(AndroidJUnit4::class)
class GenericControlsComposeHostTest {
  init {
    GenericControlsComposeFlag.setEnabledForTesting(true)
  }

  @get:Rule
  val composeTestRule = createAndroidComposeRule<VoiceRemocon>()

  @After
  fun resetFeatureFlag() {
    GenericControlsComposeFlag.setEnabledForTesting(false)
  }

  @Test
  fun genericControlsFlagOnAttachesComposeWithoutInflatingLegacyViews() {
    composeTestRule.activity.runOnUiThread {
      composeTestRule.activity.findViewById<ViewPager2>(R.id.view_pager).currentItem = 1
    }

    composeTestRule.waitUntil(timeoutMillis = 10_000) {
      composeTestRule.onAllNodesWithTag("record_button").fetchSemanticsNodes().isNotEmpty()
    }

    var attachedFragment: GenericControlsFragment? = null
    composeTestRule.activity.runOnUiThread {
      attachedFragment = composeTestRule.activity.supportFragmentManager.fragments
        .filterIsInstance<GenericControlsFragment>()
        .firstOrNull()
    }
    val fragment = attachedFragment
    assertNotNull("Generic controls fragment should be attached", fragment)
    val contentView = fragment?.view
    assertTrue("ComposeView should be the active fragment content", contentView is ComposeView)
    assertNull(
      "Legacy record button should not be inflated in the Compose branch",
      contentView?.findViewById<View>(R.id.record_button)
    )

    composeTestRule.onNodeWithTag("record_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("stop_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("play_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("pause_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("start_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("delete_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("amplitude_textview").assertIsDisplayed()
    composeTestRule.onNodeWithText("Amplitude: 0").assertIsDisplayed()
  }
}

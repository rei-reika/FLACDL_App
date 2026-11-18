package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("FLACDL Direct Tester", appName)
  }

  @Test
  fun `verify bottom navigation tabs and demo mode flow`() {
    // 1. Dashboard is displayed initially
    composeTestRule.onNodeWithText("Diagnostik Jalur Pengujian").assertIsDisplayed()
    composeTestRule.onNodeWithTag("badge_vps_status").assertIsDisplayed()
    composeTestRule.onNodeWithTag("badge_worker_status").assertIsDisplayed()

    // 2. Check all 5 Bottom Navigation tabs open the right screen
    composeTestRule.onNodeWithTag("nav_search").performClick()
    composeTestRule.onNodeWithText("Pencarian Lagu Amazon").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_handoff").performClick()
    composeTestRule.onNodeWithText("Pembuatan Handoff Worker").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_worker").performClick()
    composeTestRule.onNodeWithText("Monitor Lucida Worker").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_download").performClick()
    composeTestRule.onNodeWithText("Direct Download Worker").assertIsDisplayed()

    // 3. Return to Dashboard, scroll to Start New Test, and verify it opens Search
    composeTestRule.onNodeWithTag("nav_dashboard").performClick()
    composeTestRule.onNodeWithTag("dashboard_lazy_column")
      .performScrollToNode(hasTestTag("start_new_test_button"))
    composeTestRule.onNodeWithTag("start_new_test_button").performClick()
    composeTestRule.onNodeWithText("Pencarian Lagu Amazon").assertIsDisplayed()
  }
}

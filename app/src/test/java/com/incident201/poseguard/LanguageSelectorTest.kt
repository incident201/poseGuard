package com.incident201.poseguard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.incident201.poseguard.ui.OnboardingScreen
import com.incident201.poseguard.ui.theme.MyApplicationTheme
import com.incident201.poseguard.viewmodel.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp")
class LanguageSelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun allLanguagesAreReachableOnSmallOnboardingScreen() {
        var selected by mutableStateOf(AppLanguage.English)
        compose.setContent {
            MyApplicationTheme {
                OnboardingScreen(
                    language = selected,
                    includeLanguageSlide = true,
                    onLanguageChanged = { selected = it },
                    onFinished = {}
                )
            }
        }

        val choices = listOf(
            Triple("Español", AppLanguage.Spanish, "Siguiente"),
            Triple("Italiano", AppLanguage.Italian, "Avanti"),
            Triple("Deutsch", AppLanguage.German, "Weiter"),
            Triple("Français", AppLanguage.French, "Suivant"),
            Triple("Русский", AppLanguage.Russian, "Далее"),
            Triple("English", AppLanguage.English, "Next")
        )
        for ((label, language, nextLabel) in choices) {
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(language, selected) }
            compose.onNodeWithText(nextLabel).assertIsDisplayed()
        }
    }
}

package com.incident201.poseguard

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.incident201.poseguard.viewmodel.AppLanguage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalizationTest {
    @Test
    fun allLanguagesHaveCompleteResourcesAndMatchingPlaceholders() {
        val resourceRoot = listOf(File("src/main/res"), File("app/src/main/res"))
            .first { it.isDirectory }
        val source = readStrings(File(resourceRoot, "values/strings.xml"))
        val placeholders = Regex("""%\d+\$[ds]|\{minutes\}""")

        for (language in AppLanguage.entries) {
            val qualifier = if (language == AppLanguage.English) "" else "-${language.locale.language}"
            val translated = readStrings(File(resourceRoot, "values$qualifier/strings.xml"))
            assertEquals("Missing or extra strings for $language", source.keys, translated.keys)
            for ((key, original) in source) {
                val value = translated.getValue(key)
                assertTrue("$language/$key is empty", value.isNotBlank())
                assertEquals(
                    "$language/$key has incompatible placeholders",
                    placeholders.findAll(original).map { it.value }.sorted().toList(),
                    placeholders.findAll(value).map { it.value }.sorted().toList()
                )
            }
        }
    }

    @Test
    fun selectedLanguageResolvesCompiledResourcesRegardlessOfDeviceLanguage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deviceConfig = Configuration(context.resources.configuration).apply {
            setLocale(AppLanguage.Russian.locale)
        }
        val deviceContext = context.createConfigurationContext(deviceConfig)
        val expected = mapOf(
            AppLanguage.English to "Settings",
            AppLanguage.Russian to "Настройки",
            AppLanguage.Spanish to "Ajustes",
            AppLanguage.Italian to "Impostazioni",
            AppLanguage.German to "Einstellungen",
            AppLanguage.French to "Paramètres"
        )
        for ((language, label) in expected) {
            val config = Configuration(deviceContext.resources.configuration).apply {
                setLocale(language.locale)
            }
            val resources = deviceContext.createConfigurationContext(config).resources
            assertEquals(label, resources.getString(R.string.settings))
            assertTrue(resources.getString(R.string.violations_counter, 3).contains("3"))
            assertTrue(resources.getString(R.string.start_countdown, 5).contains("5"))
            assertTrue(resources.getString(R.string.intiface_selected_device, "Test device").contains("Test device"))
            val template = resources.getString(R.string.penalty_added_to_timer_template)
            assertTrue(template.contains("{minutes}"))
            assertFalse(template.replace("{minutes}", "3").contains("{minutes}"))
        }
    }

    private fun readStrings(file: File): Map<String, String> {
        val strings = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(file).getElementsByTagName("string")
        return buildMap {
            for (index in 0 until strings.length) {
                val node = strings.item(index)
                val name = node.attributes.getNamedItem("name").nodeValue
                assertFalse("Duplicate string $name in $file", containsKey(name))
                put(name, node.textContent)
            }
        }
    }
}

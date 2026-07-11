package com.dheyab.qiyas.safety

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Mandatory test 6 (spec §13): Hard Rule 1 enforced by CI. No medication or
 * dosing vocabulary may appear in recommendations.json or either strings.xml.
 */
class ForbiddenWordsTest {

    private val forbidden = listOf(
        "insulin", "metformin", "dose", "dosage", "mg tablet",
        "أنسولين", "جرعة", "ميتفورمين", "حبة", "قرص دواء",
    )

    private val scannedFiles = listOf(
        "src/main/assets/recommendations.json",
        "src/main/res/values/strings.xml",
        "src/main/res/values-ar/strings.xml",
    )

    private fun resolve(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.exists() }
            ?: error("Missing file under test: $path (cwd=${File(".").absolutePath})")

    @Test
    fun noForbiddenWordsInSafetyCriticalFiles() {
        for (path in scannedFiles) {
            val content = resolve(path).readText().lowercase()
            for (word in forbidden) {
                assertWithMessage("Hard Rule 1 violation: \"$word\" found in $path")
                    .that(content.contains(word.lowercase()))
                    .isFalse()
            }
        }
    }
}

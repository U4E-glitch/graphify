package com.dheyab.qiyas.safety

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Mandatory test 7 (spec §13): Hard Rule 7 — the manifest must never request
 * android.permission.INTERNET. The absence of the permission IS the privacy
 * guarantee.
 */
class ManifestTest {

    @Test
    fun manifestDoesNotRequestInternet() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.exists() } ?: error("AndroidManifest.xml not found")

        // Strip XML comments, then look for an actual permission declaration.
        val xml = manifest.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val declaresInternet = Regex(
            "<uses-permission[^>]*android\\.permission\\.INTERNET"
        ).containsMatchIn(xml)

        assertWithMessage("Hard Rule 7 violation: android.permission.INTERNET declared in the manifest")
            .that(declaresInternet)
            .isFalse()
    }
}

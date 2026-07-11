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

        // Strip XML comments; an INTERNET element is only allowed as a merger
        // REMOVAL rule (tools:node="remove"), which strips it from the built APK.
        val xml = manifest.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val internetElements = Regex("<uses-permission[^>]*android\\.permission\\.INTERNET[^>]*>")
            .findAll(xml).map { it.value }.toList()
        val realGrants = internetElements.filterNot { it.contains("tools:node=\"remove\"") }

        assertWithMessage("Hard Rule 7 violation: android.permission.INTERNET granted in the manifest")
            .that(realGrants)
            .isEmpty()
    }

    @Test
    fun mergedManifestHasNoInternetPermission() {
        // The real guarantee is the MERGED manifest (library permissions merge in).
        // Validated when a build output is present; the build task always runs it.
        val merged = listOf(
            File("build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"),
            File("app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"),
        ).firstOrNull { it.exists() } ?: return

        val grantsInternet = Regex("<uses-permission[^>]*android\\.permission\\.INTERNET")
            .containsMatchIn(merged.readText())
        assertWithMessage("Hard Rule 7 violation: android.permission.INTERNET present in the MERGED manifest")
            .that(grantsInternet)
            .isFalse()
    }
}

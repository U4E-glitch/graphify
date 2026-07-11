# Qiyas (قياس)

Personal glucose & blood-pressure tracker for Android. Bilingual English/Arabic, fully offline.
Built to `qiyas_master_spec_v0.md` (spec v1.0).

## Highlights

- **100% local data** — the manifest declares no `android.permission.INTERNET`; a unit test enforces its absence (Hard Rule 7).
- **Safety-first entry flow** — every reading requires a context tag before Save enables (Hard Rule 2); emergency-zone readings (severe/low hypo, ≥300 mg/dL, BP ≥180/120) raise a blocking alert at save time with the exact bilingual copy from the spec (Hard Rule 6, Appendix B).
- **Data-driven classification** — all thresholds load from `app/src/main/assets/thresholds.json` (Hard Rule 4); `ZoneClassifier` is pure Kotlin with exhaustive parameterized boundary tests.
- **Canonical mg/dL storage** — mmol/L exists only at the display/input layer (Hard Rule 3), with round-trip conversion tests.
- **Latin digits everywhere** — medical values render with Latin digits in both locales; input is normalized from Eastern Arabic/Persian digits before parsing (Hard Rule 5).
- **Weekly report engine** — pure-Kotlin `ReportBuilder` computes aggregates, trends vs the previous week, and data-driven flags whose lifestyle-only copy lives in `assets/recommendations.json` (Appendix C). A forbidden-words test bans medication/dosing vocabulary (Hard Rule 1).
- **Multi-profile schema** — profiles table + FK from day one; v0 UI uses the auto-seeded default profile (Hard Rule 8).
- **Export** — weekly report as A4 PDF (RTL-correct in Arabic), all data as UTF-8-BOM CSV, both via `ACTION_CREATE_DOCUMENT`.
- **Weekly notification** — WorkManager job at week start 09:00 generates the finished week's report and posts a low-importance notification.

## Stack

Kotlin 2.2 · Jetpack Compose + Material 3 · Room (schema export on) · DataStore · Hilt · Compose Navigation · WorkManager · kotlinx.serialization · Vico charts · JUnit4 + Truth.

## Build

```bash
cd qiyas
./gradlew :app:assembleDebug        # needs ANDROID_HOME / local.properties
./gradlew :app:testDebugUnitTest    # runs all mandatory spec §13 tests
```

## Test suite (spec §13)

1. Digit normalization (Eastern Arabic ٠–٩, Persian ۰–۹, ٫ and `,` separators, mixed strings)
2. Unit conversion round-trips + rounding at every §7 boundary
3. Zone boundaries, parameterized across every context + worse-of BP rule
4. Flag rules — one synthetic week firing / one not firing per flag
5. Report math — means, percentages, trend deltas, empty-group omission
6. Forbidden-words scan over `recommendations.json` + both `strings.xml`
7. Manifest test — `android.permission.INTERNET` absent

## Remaining manual QA (spec Phase 8 checklist — needs a device/emulator)

- RTL audit of every screen in Arabic
- Latin digits verified on every value in the Arabic UI
- All four blocking alerts triggered in both languages
- PDF opens correctly in the Google Drive viewer in both languages
- Edit/delete recomputes zones (covered by design: the edit flow re-runs the classifier)
- Week rollover generates the report + notification

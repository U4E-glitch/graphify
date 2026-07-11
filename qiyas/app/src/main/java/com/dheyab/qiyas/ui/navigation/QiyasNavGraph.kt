package com.dheyab.qiyas.ui.navigation

/** Navigation routes (spec §9). */
object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val ADD_GLUCOSE = "add_glucose?readingId={readingId}"
    const val ADD_BP = "add_bp?readingId={readingId}"
    const val HISTORY = "history"
    const val REPORT = "report"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    fun addGlucose(readingId: Long? = null) =
        if (readingId == null) "add_glucose?readingId=-1" else "add_glucose?readingId=$readingId"

    fun addBp(readingId: Long? = null) =
        if (readingId == null) "add_bp?readingId=-1" else "add_bp?readingId=$readingId"
}

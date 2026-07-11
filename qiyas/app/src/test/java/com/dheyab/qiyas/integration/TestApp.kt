package com.dheyab.qiyas.integration

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.dheyab.qiyas.data.db.QiyasDatabase
import com.dheyab.qiyas.data.repo.ReadingRepository
import com.dheyab.qiyas.data.settings.SettingsRepository
import com.dheyab.qiyas.domain.ThresholdConfig
import com.dheyab.qiyas.domain.ZoneClassifier
import com.dheyab.qiyas.domain.report.RecommendationsConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Wires the REAL app graph by hand for Robolectric integration tests:
 * real Room over real SQLite, real DataStore over a temp file, and the real
 * classifier/recommendations parsed from the shipped asset files.
 */
class TestGraph(context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: QiyasDatabase = Room.inMemoryDatabaseBuilder(context, QiyasDatabase::class.java)
        .addCallback(QiyasDatabase.SeedCallback())
        .allowMainThreadQueries()
        .build()

    val readingRepository = ReadingRepository(database.readingDao())

    val settingsRepository = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("settings", ".preferences_pb")
        }
    )

    val thresholdConfig: ThresholdConfig = context.assets.open("thresholds.json")
        .bufferedReader().use { ThresholdConfig.fromJson(it.readText()) }

    val classifier = ZoneClassifier(thresholdConfig)

    val recommendations: RecommendationsConfig = context.assets.open("recommendations.json")
        .bufferedReader().use { RecommendationsConfig.fromJson(it.readText()) }

    val photoStore = com.dheyab.qiyas.data.photo.PhotoStore(context)

    val scanner = com.dheyab.qiyas.data.photo.MeterScanner()
}

/** Plain Application for Robolectric — avoids booting the Hilt app class. */
class PlainTestApp : Application()

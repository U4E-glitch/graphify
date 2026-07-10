package com.dheyab.qiyas.data.repo

import com.dheyab.qiyas.data.db.WeeklyReportDao
import com.dheyab.qiyas.data.db.WeeklyReportEntity
import com.dheyab.qiyas.data.settings.SettingsRepository
import com.dheyab.qiyas.domain.report.ReportBuilder
import com.dheyab.qiyas.domain.report.ReportModel
import com.dheyab.qiyas.domain.report.WeekMath
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

@Singleton
class ReportRepository @Inject constructor(
    private val readingRepository: ReadingRepository,
    private val weeklyReportDao: WeeklyReportDao,
    private val settingsRepository: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val builder = ReportBuilder()

    suspend fun currentWeekStart(): LocalDate {
        val weekStartDay = settingsRepository.settings.first().weekStart
        return WeekMath.weekStartFor(LocalDate.now(), weekStartDay)
    }

    /**
     * Builds the report for the week starting at [weekStart], persists the
     * regenerated payload (spec §10 regeneration rule), and returns it.
     */
    suspend fun generate(profileId: Long, weekStart: LocalDate): ReportModel {
        val zone = ZoneId.systemDefault()
        val (from, to) = WeekMath.weekBoundsMillis(weekStart, zone)
        val (prevFrom, prevTo) = WeekMath.weekBoundsMillis(weekStart.minusWeeks(1), zone)
        val week = readingRepository.readingsBetween(profileId, from, to)
        val previous = readingRepository.readingsBetween(profileId, prevFrom, prevTo)
        val model = builder.build(weekStart, week, previous)
        weeklyReportDao.upsert(
            WeeklyReportEntity(
                id = weeklyReportDao.getByWeek(profileId, model.weekStartDate)?.id ?: 0,
                profileId = profileId,
                weekStartDate = model.weekStartDate,
                generatedAt = System.currentTimeMillis(),
                jsonPayload = json.encodeToString(ReportModel.serializer(), model),
            )
        )
        return model
    }
}

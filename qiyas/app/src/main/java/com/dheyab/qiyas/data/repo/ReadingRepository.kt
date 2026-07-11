package com.dheyab.qiyas.data.repo

import com.dheyab.qiyas.data.db.ReadingDao
import com.dheyab.qiyas.data.db.toDomain
import com.dheyab.qiyas.data.db.toEntity
import com.dheyab.qiyas.domain.model.Reading
import com.dheyab.qiyas.domain.model.ReadingType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ReadingRepository @Inject constructor(
    private val readingDao: ReadingDao,
) {
    suspend fun insert(reading: Reading): Long = readingDao.insert(reading.toEntity())

    suspend fun update(reading: Reading) = readingDao.update(reading.toEntity())

    suspend fun delete(id: Long) = readingDao.deleteById(id)

    suspend fun getById(id: Long): Reading? = readingDao.getById(id)?.toDomain()

    fun observeAll(profileId: Long): Flow<List<Reading>> =
        readingDao.observeAll(profileId).map { list -> list.map { it.toDomain() } }

    fun observeByType(profileId: Long, type: ReadingType): Flow<List<Reading>> =
        readingDao.observeByType(profileId, type.name).map { list -> list.map { it.toDomain() } }

    fun observeLatestOfType(profileId: Long, type: ReadingType): Flow<Reading?> =
        readingDao.observeLatestOfType(profileId, type.name).map { it?.toDomain() }

    suspend fun readingsBetween(profileId: Long, fromInclusive: Long, toExclusive: Long): List<Reading> =
        readingDao.readingsBetween(profileId, fromInclusive, toExclusive).map { it.toDomain() }

    fun observeBetweenOfType(
        profileId: Long,
        type: ReadingType,
        fromInclusive: Long,
        toExclusive: Long,
    ): Flow<List<Reading>> =
        readingDao.observeBetweenOfType(profileId, type.name, fromInclusive, toExclusive)
            .map { list -> list.map { it.toDomain() } }

    fun observeCountBetweenOfType(
        profileId: Long,
        type: ReadingType,
        fromInclusive: Long,
        toExclusive: Long,
    ): Flow<Int> = readingDao.observeCountBetweenOfType(profileId, type.name, fromInclusive, toExclusive)

    suspend fun allForExport(profileId: Long): List<Reading> =
        readingDao.allForExport(profileId).map { it.toDomain() }
}

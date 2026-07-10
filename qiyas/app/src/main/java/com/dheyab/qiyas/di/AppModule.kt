package com.dheyab.qiyas.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.dheyab.qiyas.data.db.ProfileDao
import com.dheyab.qiyas.data.db.QiyasDatabase
import com.dheyab.qiyas.data.db.ReadingDao
import com.dheyab.qiyas.data.db.WeeklyReportDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): QiyasDatabase =
        Room.databaseBuilder(context, QiyasDatabase::class.java, "qiyas.db")
            .addCallback(QiyasDatabase.SeedCallback())
            .build()

    @Provides
    fun provideProfileDao(db: QiyasDatabase): ProfileDao = db.profileDao()

    @Provides
    fun provideReadingDao(db: QiyasDatabase): ReadingDao = db.readingDao()

    @Provides
    fun provideWeeklyReportDao(db: QiyasDatabase): WeeklyReportDao = db.weeklyReportDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore
}

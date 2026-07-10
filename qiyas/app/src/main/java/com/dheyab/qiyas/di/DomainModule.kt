package com.dheyab.qiyas.di

import android.content.Context
import com.dheyab.qiyas.domain.ThresholdConfig
import com.dheyab.qiyas.domain.ZoneClassifier
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainModule {

    @Provides
    @Singleton
    fun provideThresholdConfig(@ApplicationContext context: Context): ThresholdConfig =
        context.assets.open("thresholds.json").bufferedReader().use { reader ->
            ThresholdConfig.fromJson(reader.readText())
        }

    @Provides
    @Singleton
    fun provideZoneClassifier(config: ThresholdConfig): ZoneClassifier = ZoneClassifier(config)
}

package com.dheyab.qiyas.di

import android.content.Context
import com.dheyab.qiyas.domain.ThresholdConfig
import com.dheyab.qiyas.domain.ZoneClassifier
import com.dheyab.qiyas.domain.report.RecommendationsConfig
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

    @Provides
    @Singleton
    fun provideRecommendationsConfig(@ApplicationContext context: Context): RecommendationsConfig =
        context.assets.open("recommendations.json").bufferedReader().use { reader ->
            RecommendationsConfig.fromJson(reader.readText())
        }
}

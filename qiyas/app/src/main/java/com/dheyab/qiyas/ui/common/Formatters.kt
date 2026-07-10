package com.dheyab.qiyas.ui.common

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.dheyab.qiyas.core.NumberUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Date/time text may use localized month/day names, but digits must stay Latin
 * in both locales (Hard Rule 5) — hence the toLatinDigits pass.
 */
object Formatters {

    fun dateTime(millis: Long, locale: Locale): String {
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
        return NumberUtils.toLatinDigits(formatter.format(Instant.ofEpochMilli(millis)))
    }

    fun date(millis: Long, locale: Locale): String {
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
        return NumberUtils.toLatinDigits(formatter.format(Instant.ofEpochMilli(millis)))
    }

    fun time(millis: Long, locale: Locale): String {
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
        return NumberUtils.toLatinDigits(formatter.format(Instant.ofEpochMilli(millis)))
    }

    fun relative(millis: Long): String =
        NumberUtils.toLatinDigits(
            DateUtils.getRelativeTimeSpanString(
                millis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
        )
}

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

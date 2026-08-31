// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import java.text.SimpleDateFormat
import java.util.Calendar

fun getTimestamp(context: Context): String = getTimestampFormatter(context).format(Calendar.getInstance().time)

fun getTimestampFormatter(context: Context): SimpleDateFormat {
    val format = context.prefs().getString(Settings.PREF_TIMESTAMP_FORMAT, Defaults.PREF_TIMESTAMP_FORMAT)
    return runCatching<SimpleDateFormat> { SimpleDateFormat(format, Settings.getValues().mLocale) }.getOrNull()
        ?: SimpleDateFormat(Defaults.PREF_TIMESTAMP_FORMAT, Settings.getValues().mLocale)
}

fun checkTimestampFormat(format: String) = runCatching { SimpleDateFormat(format, Settings.getValues().mLocale) }.isSuccess

/**
 * Return the start-of-day timestamp for [millis] in the device's default timezone.
 * Uses Calendar to zero HOUR_OF_DAY/MINUTE/SECOND/MILLISECOND, which is
 * timezone-correct — unlike the UTC-based `millis / 86400000 * 86400000` shortcut
 * that breaks for timezones ahead of UTC (e.g. IST, UTC+5:30).
 */
fun startOfLocalDay(millis: Long = System.currentTimeMillis()): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

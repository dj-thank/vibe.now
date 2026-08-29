package app.setlog.capture.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(Locale.ROOT, minutes, seconds)
    }
}

fun formatDateTime(epochMs: Long, locale: Locale = Locale.getDefault()): String {
    val pattern = if (locale.language == Locale.JAPANESE.language) {
        "yyyy/M/d H:mm:ss"
    } else {
        "MMM d, yyyy HH:mm:ss"
    }
    return SimpleDateFormat(pattern, locale).format(Date(epochMs))
}

fun defaultTitleTimestamp(epochMs: Long): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(epochMs))

fun iso8601Utc(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(epochMs))

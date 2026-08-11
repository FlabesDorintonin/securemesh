package dev.securemesh.commander.core.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun ageLabel(timestampEpochMs: Long, now: Long = System.currentTimeMillis()): String {
    val ms = (now - timestampEpochMs).coerceAtLeast(0)
    return when {
        ms < 1_000 -> "сейчас"
        ms < 60_000 -> "${ms / 1_000} сек"
        ms < 3_600_000 -> "${ms / 60_000} мин"
        ms < 86_400_000 -> "${ms / 3_600_000} ч"
        else -> "${ms / 86_400_000} дн"
    }
}

fun clockLabel(timestampEpochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampEpochMs))

fun percent(value: Double?): String = value?.let { "%.1f%%".format(Locale.US, it * 100) } ?: "—"
fun dbm(value: Int?): String = value?.let { "$it dBm" } ?: "—"
fun snr(value: Double?): String = value?.let { "%+.1f dB".format(Locale.US, it) } ?: "—"
fun voltage(value: Double?): String = value?.let { "%.2f В".format(Locale.US, it) } ?: "—"
fun coordinate(value: Double): String = "%.6f".format(Locale.US, value)
fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "Отличный сигнал"
    rssi >= -75 -> "Хороший сигнал"
    rssi >= -90 -> "Средний сигнал"
    rssi >= -105 -> "Слабый сигнал"
    else -> "Очень слабый сигнал"
}

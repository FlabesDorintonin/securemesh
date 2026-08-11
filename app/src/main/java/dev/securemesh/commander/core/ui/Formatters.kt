package dev.securemesh.commander.core.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

fun ageLabel(timestampEpochMs: Long, now: Long = System.currentTimeMillis()): String {
    val ms = (now - timestampEpochMs).coerceAtLeast(0)
    return when {
        ms < 1_000 -> "now"
        ms < 60_000 -> "%.1fs".format(Locale.US, ms / 1000.0)
        ms < 3_600_000 -> "${ms / 60_000}m"
        else -> "${ms / 3_600_000}h"
    }
}

fun clockLabel(timestampEpochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampEpochMs))

fun percent(value: Double?): String = value?.let { "%.1f%%".format(Locale.US, it * 100) } ?: "—"
fun dbm(value: Int?): String = value?.let { "$it dBm" } ?: "—"
fun snr(value: Double?): String = value?.let { "%+.1f dB".format(Locale.US, it) } ?: "—"
fun voltage(value: Double?): String = value?.let { "%.2f V".format(Locale.US, it) } ?: "—"
fun coordinate(value: Double): String = "%.6f".format(Locale.US, value)
fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "Excellent"
    rssi >= -75 -> "Good"
    rssi >= -90 -> "Fair"
    rssi >= -105 -> "Weak"
    else -> "Very weak"
}

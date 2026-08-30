package dev.securemesh.commander.domain.service

fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "Excellent"
    rssi >= -75 -> "Good"
    rssi >= -90 -> "Degraded"
    else -> "Critical"
}

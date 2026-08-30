package dev.securemesh.commander.domain.service

import dev.securemesh.commander.domain.model.MeshLink
import kotlin.math.roundToInt

enum class LinkHealthGrade { EXCELLENT, GOOD, DEGRADED, CRITICAL, LOST, UNKNOWN }

data class LinkHealthAssessment(
    val grade: LinkHealthGrade,
    val score: Int?,
    val label: String,
    val summary: String,
    val reasons: List<String> = emptyList(),
)

/**
 * Display-only operator assessment. It does not replace firmware/VANGUARD metrics
 * and is never sent back to the node as routing input.
 */
fun assessLinkHealth(link: MeshLink?, nowEpochMs: Long = System.currentTimeMillis()): LinkHealthAssessment {
    if (link == null) return LinkHealthAssessment(LinkHealthGrade.UNKNOWN, null, "Нет данных", "Нет измерений радиоканала")
    val ageMs = link.lastSeenEpochMs?.let { (nowEpochMs - it).coerceAtLeast(0L) }
    if (ageMs != null && ageMs > 30_000L) {
        return LinkHealthAssessment(LinkHealthGrade.LOST, 0, "Связь потеряна", "Свежих данных нет больше 30 секунд", listOf("Нет свежих пакетов"))
    }

    val weighted = mutableListOf<Pair<Double, Double>>()
    val reasons = mutableListOf<String>()
    link.rssi?.let {
        val score = ((it + 120).coerceIn(0, 65) / 65.0) * 100.0
        weighted += score to 0.30
        if (it < -105) reasons += "Очень слабый уровень сигнала"
        else if (it < -95) reasons += "Сигнал заметно ослаблен"
    }
    link.snr?.let {
        val score = ((it + 20.0).coerceIn(0.0, 30.0) / 30.0) * 100.0
        weighted += score to 0.25
        if (it < -10.0) reasons += "Высокий уровень помех"
        else if (it < -4.0) reasons += "Есть радиопомехи"
    }
    link.pdr?.let {
        val normalized = if (it <= 1.0) it * 100.0 else it
        weighted += normalized.coerceIn(0.0, 100.0) to 0.35
        if (normalized < 70.0) reasons += "Теряется много пакетов"
        else if (normalized < 90.0) reasons += "Есть потери пакетов"
    }
    val retries = link.retries ?: 0
    if (retries >= 3) reasons += "Много повторных передач"
    else if (retries > 0) reasons += "Есть повторные передачи"

    if (weighted.isEmpty()) return LinkHealthAssessment(LinkHealthGrade.UNKNOWN, null, "Нет данных", "Недостаточно измерений", reasons)
    val base = weighted.sumOf { it.first * it.second } / weighted.sumOf { it.second }
    val agePenalty = ageMs?.let { ((it / 1000.0) * 1.5).coerceAtMost(25.0) } ?: 0.0
    val score = (base - retries.coerceAtMost(5) * 6.0 - agePenalty).coerceIn(0.0, 100.0).roundToInt()
    val grade = when {
        score >= 82 -> LinkHealthGrade.EXCELLENT
        score >= 64 -> LinkHealthGrade.GOOD
        score >= 43 -> LinkHealthGrade.DEGRADED
        else -> LinkHealthGrade.CRITICAL
    }
    val label = when (grade) {
        LinkHealthGrade.EXCELLENT -> "Отличная"
        LinkHealthGrade.GOOD -> "Хорошая"
        LinkHealthGrade.DEGRADED -> "Нестабильная"
        LinkHealthGrade.CRITICAL -> "Слабая"
        LinkHealthGrade.LOST -> "Связь потеряна"
        LinkHealthGrade.UNKNOWN -> "Нет данных"
    }
    val summary = when (grade) {
        LinkHealthGrade.EXCELLENT -> "Связь стабильна, запас хороший"
        LinkHealthGrade.GOOD -> "Связь пригодна для нормальной работы"
        LinkHealthGrade.DEGRADED -> "Возможны задержки и повторные передачи"
        LinkHealthGrade.CRITICAL -> "Высокий риск потери пакетов"
        LinkHealthGrade.LOST -> "Узел не отвечает"
        LinkHealthGrade.UNKNOWN -> "Недостаточно измерений"
    }
    return LinkHealthAssessment(grade, score, label, summary, reasons.distinct())
}

fun bleProximityLabel(rssi: Int): String = when {
    rssi >= -55 -> "Очень близко"
    rssi >= -68 -> "Рядом"
    rssi >= -80 -> "Недалеко"
    rssi >= -92 -> "Далеко"
    else -> "На границе обнаружения"
}

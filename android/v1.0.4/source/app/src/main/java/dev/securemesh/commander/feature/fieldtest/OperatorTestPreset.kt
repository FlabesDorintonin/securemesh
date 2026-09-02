package dev.securemesh.commander.feature.fieldtest

import dev.securemesh.commander.domain.model.FieldTestConfig
import dev.securemesh.commander.domain.model.FieldTestMode

enum class OperatorTestPreset(
    val title: String,
    val description: String,
    val packetCount: Int,
    val intervalMs: Long,
    val payloadBytes: Int,
) {
    QUICK(
        title = "Быстрая проверка",
        description = "Короткая проверка перед работой",
        packetCount = 30,
        intervalMs = 500L,
        payloadBytes = 16,
    ),
    STANDARD(
        title = "Обычная проверка",
        description = "Основной вариант для проверки связи",
        packetCount = 100,
        intervalMs = 1000L,
        payloadBytes = 32,
    ),
    LONG(
        title = "Длительная проверка",
        description = "Больше передач для наблюдения во времени",
        packetCount = 200,
        intervalMs = 1500L,
        payloadBytes = 48,
    );

    fun toConfig(sourceNodeId: String, targetNodeId: String, mode: FieldTestMode): FieldTestConfig = FieldTestConfig(
        source = sourceNodeId,
        target = targetNodeId,
        mode = mode,
        packetCount = packetCount,
        intervalMs = intervalMs,
        payloadBytes = payloadBytes,
    )
}

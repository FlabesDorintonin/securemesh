package dev.securemesh.commander.core.ui

fun Enum<*>?.ruLabel(): String {
    val key = this?.name ?: return "Нет данных"
    return when (key) {
        // Legacy enum name retained to avoid widening the migration surface; user-visible current demo is v0.6.
        "CURRENT_FIRMWARE_V05" -> "Текущая прошивка v0.6"
        "FUTURE_DEMO" -> "Демо будущих функций"

        "MEMBER" -> "Участник"
        "RELAY" -> "Ретранслятор"
        "TEAM_LEADER" -> "Командир группы"
        "OPERATOR" -> "Оператор"
        "COMMANDER" -> "Командир"
        "ADMIN" -> "Администратор"
        "DEVELOPMENT" -> "Разработка"
        "UNKNOWN" -> "Неизвестно"

        "AUTHENTICATED" -> "Подтверждена"
        "UNAUTHENTICATED", "NOT_AUTHENTICATED" -> "Не подтверждена"
        "PAIRING_REQUIRED" -> "Нужно сопряжение"
        "AUTHENTICATING" -> "Проверка доступа"
        "ESTABLISHED" -> "Установлена"
        "NOT_CONFIGURED" -> "Не настроена"
        "PENDING" -> "Ожидание"

        "DIRECT" -> "Прямой"
        "STATIC" -> "Статический"
        "DYNAMIC" -> "Динамический"
        "ROUTED" -> "Через сеть"
        "AUTO" -> "Авто"
        "STALE" -> "Устарело"

        "QUEUED" -> "В очереди"
        "ROUTING" -> "Маршрутизация"
        "SENDING" -> "Отправка"
        "HOP_PROGRESS" -> "Передача по узлам"
        "FINAL_CONFIRMATION_PENDING" -> "Ждём финальное подтверждение"
        "DELIVERED" -> "Доставлено"
        "FAILED" -> "Ошибка"
        "EXPIRED" -> "Истекло"
        "CONFIRMED_RECEIVED" -> "Получено"

        "ACKED" -> "Подтверждено"
        "NACKED" -> "Отклонено"
        "TIMEOUT" -> "Тайм-аут"
        "UNAVAILABLE" -> "Недоступно"

        "SYSTEM" -> "Система"
        "RADIO" -> "Радио"
        "MESSAGES" -> "Сообщения"
        "GPS" -> "GPS"
        "SECURITY" -> "Безопасность"
        "SOS" -> "SOS"

        "BONDED" -> "Сопряжено"
        "BONDING" -> "Сопряжение"
        "NOT_BONDED" -> "Не сопряжено"

        "TRUSTED_SECUREMESH" -> "Доверенный SecureMesh"
        "KNOWN_SECUREMESH" -> "Узел SecureMesh"
        "SECUREMESH_CANDIDATE" -> "Возможный SecureMesh"
        "UNKNOWN_BLE" -> "Неизвестное BLE"

        "EXCELLENT" -> "Отлично"
        "GOOD" -> "Хорошо"
        "DEGRADED" -> "Слабо"
        "CRITICAL" -> "Критично"

        "BLE" -> "Bluetooth LE"
        "MOCK" -> "Демо"

        "MESSAGING" -> "Сообщения"
        "FIELD_TEST" -> "Полевой тест"
        "STATIC_ROUTING" -> "Статические маршруты"
        "BLE_CONTROL" -> "BLE-управление"
        "NETWORK_DIAGNOSTICS" -> "Диагностика сети"
        "OTA" -> "Обновление прошивки"
        "SENSORS" -> "Датчики"

        "NAME" -> "По имени"
        "STATUS" -> "По статусу"
        "BATTERY" -> "По заряду"
        "SIGNAL" -> "По связи"
        "RSSI" -> "По RSSI"

        "FIX" -> "Есть фиксация"
        "NO_FIX" -> "Нет фиксации"
        "INVALID" -> "Недействительно"

        else -> key.lowercase().replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

fun localizedTechnicalText(text: String?): String {
    if (text.isNullOrBlank()) return "Нет данных"
    return text
        .replace("DEMO PROFILE ACTIVE", "Демо-профиль активен", ignoreCase = true)
        .replace("DEVELOPMENT SCENARIO APPLIED", "Сценарий разработчика применён", ignoreCase = true)
        .replace("SECUREMESH SESSION ESTABLISHED", "Защищённая сессия установлена", ignoreCase = true)
        .replace("BLE CONNECTED / SECUREMESH UNKNOWN", "BLE подключён · SecureMesh не распознан", ignoreCase = true)
        .replace("STATIC ROUTE UPDATED", "Статический маршрут обновлён", ignoreCase = true)
        .replace("STATIC ROUTE REMOVED", "Статический маршрут удалён", ignoreCase = true)
        .replace("FIELD TEST COMPLETE", "Полевой тест завершён", ignoreCase = true)
        .replace("MESSAGE #", "Сообщение #", ignoreCase = true)
        .replace(" E2E UNKNOWN", " · сквозная доставка не подтверждена", ignoreCase = true)
        .replace(" DELIVERED", " · доставлено", ignoreCase = true)
        .replace(" QUEUED", " · в очереди", ignoreCase = true)
        .replace("Future demo end-to-end confirmation", "Демо: сквозное подтверждение доставки", ignoreCase = true)
        .replace(
            "All observed hop ACKs succeeded; this demo profile has no end-to-end delivery confirmation",
            "Все hop-ACK получены; обычного сквозного подтверждения доставки в текущей прошивке нет",
            ignoreCase = true,
        )
        .replace("E2E PDR unavailable; hop telemetry captured", "Сквозной PDR недоступен; hop-телеметрия сохранена", ignoreCase = true)
        .replace("Development scenario applied", "Сценарий разработчика применён", ignoreCase = true)
        .replace("User requested", "Отключено пользователем", ignoreCase = true)
}

fun localizedError(text: String?): String? {
    if (text.isNullOrBlank()) return null
    return text
        .replace("No SecureMesh session", "Нет активной сессии SecureMesh", ignoreCase = true)
        .replace("No session", "Нет активной сессии", ignoreCase = true)
        .replace("not granted", "нет необходимого разрешения", ignoreCase = true)
        .replace("Unknown node", "Неизвестный узел", ignoreCase = true)
        .replace("is offline", "не в сети", ignoreCase = true)
        .replace("Route unavailable", "Маршрут недоступен", ignoreCase = true)
        .replace("Field test already running", "Полевой тест уже запущен", ignoreCase = true)
        .replace("Field test source must be local node", "Источником теста должен быть локальный узел", ignoreCase = true)
}

fun deviceDisplayName(name: String?): String = when (name) {
    null, "" -> "BLE-устройство без имени"
    "SecureMesh Field Node" -> "Полевой узел SecureMesh"
    "Nearby Sensor" -> "Соседний датчик"
    "Field Node" -> "Полевой узел"
    "Command Node" -> "Командный узел"
    "Relay North" -> "Ретранслятор «Север»"
    "Team Node" -> "Узел группы"
    "Direct Node" -> "Прямой узел"
    else -> name
}

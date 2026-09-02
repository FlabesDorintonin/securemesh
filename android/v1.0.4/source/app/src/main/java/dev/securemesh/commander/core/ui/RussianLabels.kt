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

        "DIRECT" -> "Напрямую"
        "STATIC" -> "Заданный вручную"
        "DYNAMIC" -> "Найден автоматически"
        "ROUTED" -> "Через сеть"
        "AUTO" -> "Автоматически"
        "STALE" -> "Устарело"

        "QUEUED" -> "В очереди"
        "ROUTING" -> "Поиск пути"
        "SENDING" -> "Отправка"
        "HOP_PROGRESS" -> "Передача по узлам"
        "FINAL_CONFIRMATION_PENDING" -> "Ждём подтверждение доставки"
        "DELIVERED" -> "Доставлено"
        "FAILED" -> "Ошибка"
        "EXPIRED" -> "Истекло"
        "CONFIRMED_RECEIVED" -> "Получено"

        "ACKED" -> "Подтверждено"
        "NACKED" -> "Отклонено"
        "TIMEOUT" -> "Нет ответа вовремя"
        "UNAVAILABLE" -> "Недоступно"

        "SYSTEM" -> "Система"
        "RADIO" -> "Радиосвязь"
        "MESSAGES" -> "Сообщения"
        "GPS" -> "Навигация"
        "SECURITY" -> "Безопасность"
        "SOS" -> "Экстренный сигнал"

        "BONDED" -> "Сопряжено"
        "BONDING" -> "Сопряжение"
        "NOT_BONDED" -> "Не сопряжено"

        "TRUSTED_SECUREMESH" -> "Доверенный узел SecureMesh"
        "KNOWN_SECUREMESH" -> "Узел SecureMesh"
        "SECUREMESH_CANDIDATE" -> "Возможный узел SecureMesh"
        "UNKNOWN_BLE" -> "Устройство без имени"

        "EXCELLENT" -> "Отлично"
        "GOOD" -> "Хорошо"
        "DEGRADED" -> "Есть ограничения"
        "CRITICAL" -> "Требует внимания"

        "BLE" -> "Связь с телефоном"
        "MOCK" -> "Демо"

        "MESSAGING" -> "Сообщения"
        "FIELD_TEST" -> "Испытания"
        "STATIC_ROUTING" -> "Заданные маршруты"
        "BLE_CONTROL" -> "Управление с телефона"
        "NETWORK_DIAGNOSTICS" -> "Проверка сети"
        "OTA" -> "Обновление прошивки"
        "SENSORS" -> "Датчики"

        "NAME" -> "По имени"
        "STATUS" -> "По состоянию"
        "BATTERY" -> "По заряду"
        "SIGNAL" -> "По качеству связи"
        "RSSI" -> "По качеству связи"

        "FIX" -> "Координаты получены"
        "NO_FIX" -> "Координат нет"
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
        .replace("BLE CONNECTED / SECUREMESH UNKNOWN", "Телефон подключён · узел SecureMesh не распознан", ignoreCase = true)
        .replace("STATIC ROUTE UPDATED", "Заданный маршрут обновлён", ignoreCase = true)
        .replace("STATIC ROUTE REMOVED", "Заданный маршрут удалён", ignoreCase = true)
        .replace("FIELD TEST COMPLETE", "Испытание завершено", ignoreCase = true)
        .replace("MESSAGE #", "Сообщение #", ignoreCase = true)
        .replace(" E2E UNKNOWN", " · доставка до конечного узла не подтверждена", ignoreCase = true)
        .replace(" DELIVERED", " · доставлено", ignoreCase = true)
        .replace(" QUEUED", " · в очереди", ignoreCase = true)
        .replace("Future demo end-to-end confirmation", "Демо: подтверждение доставки до конечного узла", ignoreCase = true)
        .replace(
            "All observed hop ACKs succeeded; this demo profile has no end-to-end delivery confirmation",
            "Все наблюдаемые участки пути подтверждены; подтверждения доставки до конечного узла в текущей прошивке нет",
            ignoreCase = true,
        )
        .replace("E2E PDR unavailable; hop telemetry captured", "Доля доставки до конечного узла недоступна; сведения по участкам пути сохранены", ignoreCase = true)
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
        .replace("Route unavailable", "Путь недоступен", ignoreCase = true)
        .replace("Field test already running", "Испытание уже запущено", ignoreCase = true)
        .replace("Field test source must be local node", "Испытание должно запускаться от подключённого узла", ignoreCase = true)
}

fun deviceDisplayName(name: String?): String = when (name) {
    null, "" -> "Устройство без имени"
    "SecureMesh Field Node" -> "Полевой узел SecureMesh"
    "Nearby Sensor" -> "Соседний датчик"
    "Field Node" -> "Полевой узел"
    "Command Node" -> "Командный узел"
    "Relay North" -> "Ретранслятор «Север»"
    "Team Node" -> "Узел группы"
    "Direct Node" -> "Прямой узел"
    else -> name
}

package dev.securemesh.commander.domain.model

enum class DeviceUiScene(val wire: Int, val label: String) {
    HOME(0, "Главный экран"),
    MENU(1, "Меню"),
    FEATURE(2, "Раздел"),
    UNKNOWN(-1, "Неизвестно");

    companion object {
        fun fromWire(value: Int): DeviceUiScene = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class DeviceUiMenu(val wire: Int, val label: String) {
    ROOT(0, "Главное меню"),
    MESSAGING(1, "Сообщения"),
    NETWORK(2, "Сеть"),
    RADIO(3, "Радио"),
    NAVIGATION(4, "Навигация"),
    SOS(5, "SOS"),
    SECURITY(6, "Безопасность"),
    DIAGNOSTICS(7, "Диагностика"),
    SYSTEM(8, "Система"),
    QUICK(9, "Быстрые действия"),
    UNKNOWN(-1, "Неизвестное меню");

    companion object {
        fun fromWire(value: Int): DeviceUiMenu = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class DeviceUiFeature(val wire: Int, val label: String) {
    NONE(0, "Нет"),
    INBOX(1, "Входящие"),
    COMPOSE(2, "Отправить"),
    DELIVERY(3, "Доставка E2E"),
    DEFERRED_DELIVERY(4, "Отложенная доставка"),
    FRAGMENTATION(5, "Фрагменты"),
    TRAFFIC_PRIORITY(6, "Приоритеты"),
    NEIGHBORS(7, "Соседи"),
    ROUTES(8, "Маршруты"),
    TOPOLOGY(9, "Топология"),
    DYNAMIC_ROUTING(10, "Динамический маршрут"),
    ROUTE_FORECAST(11, "Прогноз маршрута"),
    OPPORTUNISTIC_RELAY(12, "Ожидающая ретрансляция"),
    LOAD_MANAGEMENT(13, "Нагрузка сети"),
    RADIO_STATUS(14, "Статус радио"),
    SPECTRUM(15, "Анализ эфира"),
    CHANNEL_CONTROL(16, "Каналы"),
    RADIO_PROFILES(17, "Профили связи"),
    ADAPTIVE_LINK(18, "Адаптивная связь"),
    POWER_CONTROL(19, "Мощность"),
    RADIO_SILENCE(20, "Радиомолчание"),
    SHORT_PACKETS(21, "Короткие пакеты"),
    GPS(22, "GPS"),
    POSITIONS(23, "Позиции узлов"),
    OFFLINE_MAP(24, "Офлайн-карта"),
    GEOZONES(25, "Геозоны"),
    NETWORK_HISTORY(26, "История сети"),
    SOS_STATUS(27, "Статус SOS"),
    SOS_SEND(28, "Отправить SOS"),
    SOS_TYPES(29, "Тип тревоги"),
    EMERGENCY_PROFILE(30, "Аварийный профиль"),
    BLE_SESSION(31, "BLE-сессия"),
    ACCESS_LOCK(32, "Автоблокировка"),
    FINGERPRINT(33, "Отпечаток"),
    ROLES_PERMISSIONS(34, "Роли и права"),
    KEY_MANAGEMENT(35, "Ключи и сессии"),
    NODE_MANAGEMENT(36, "Управление узлами"),
    CRYPTO_WIPE(37, "Криптоочистка"),
    FIRMWARE_PROTECTION(38, "Защита Flash"),
    FIELD_TEST(39, "Тест связи"),
    SELF_TEST(40, "Самотест"),
    LINK_METRICS(41, "Метрики линка"),
    EVENT_LOG(42, "Журнал событий"),
    MEMORY(43, "Память"),
    SYSTEM_OVERVIEW(44, "Обзор"),
    POWER(45, "Питание"),
    NETWORK_TIME(46, "Сетевое время"),
    POWER_SAVING(47, "Энергосбережение"),
    FIRMWARE(48, "Прошивка"),
    OTA(49, "OTA"),
    ABOUT(50, "Об устройстве"),
    UNKNOWN(-1, "Неизвестный раздел");

    companion object {
        fun fromWire(value: Int): DeviceUiFeature = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class DeviceUiAction(val wire: Int) {
    UP(1),
    DOWN(2),
    SELECT(3),
    BACK(4),
    HOME(5),
}

data class DeviceUiState(
    val modelVersion: Int,
    val scene: DeviceUiScene,
    val menu: DeviceUiMenu,
    val menuIndex: Int,
    val menuScroll: Int,
    val navigationDepth: Int,
    val feature: DeviceUiFeature,
    val oledReady: Boolean,
    val bleProtocolReady: Boolean,
    val fieldTestRunning: Boolean,
    val toastVisible: Boolean,
    val plannedFeature: Boolean,
    val hasUnread: Boolean,
    val inboxCount: Int,
    val unreadCount: Int,
    val neighborCount: Int,
    val routeCount: Int,
    val fieldTestState: Int,
    val bleState: Int,
    val messageIndex: Int,
    val neighborIndex: Int,
    val routeIndex: Int,
    val localNodeId: NodeId,
    val fieldTestId: Long,
    val fieldTestTarget: NodeId?,
    val rawScene: Int,
    val rawMenu: Int,
    val rawFeature: Int,
    val updatedAtEpochMs: Long,
)

data class OledFramebufferSnapshot(
    val snapshotId: Long,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val updatedAtEpochMs: Long,
) {
    init {
        require(width > 0 && height > 0 && height % 8 == 0) { "invalid OLED dimensions" }
        require(bytes.size == width * height / 8) { "invalid OLED framebuffer length" }
    }

    fun pixelOn(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        val index = x + (y / 8) * width
        return bytes[index].toInt() and (1 shl (y and 7)) != 0
    }
}

# SecureMesh v1.0.4 Operator

## Radio Isolation v2 — 31.08.2026

- Устранён архитектурный путь, при котором отсутствующий/неисправный E22/SX1268 мог блокировать BLE/OLED: синхронный `initializeRadio()/RadioLib begin` больше не запускается из Arduino main loop.
- Radio init/recovery вынесен в отдельную low-priority FreeRTOS worker-задачу; BLE control plane поднимается раньше radio initialization.
- Runtime radio fault теперь fail-closed отключает DIO1 и не вызывает потенциально блокирующий RadioLib cleanup из main loop.
- TX/IRQ scheduler исключает доступ к radio, пока worker выполняет initialization.
- Android OLED remote переведён на bounded последовательную очередь действий; exact framebuffer mirror больше не блокирует action path общим mutex.
- Framebuffer refresh после быстрых действий коалесцируется, fallback polling снижен до 1500 ms, сборка framebuffer выполняется в заранее выделенном буфере.
- Исправлена воспроизводимость CI: migration scripts нормализуются при повторном запуске, Android manifest исключает transient build/cache файлы, firmware manifest проверяется до упаковки, реальный `SecureMeshSecrets.h` запрещён в artifact.
- BLE application protocol v2, fragment transport v1 и VANGUARD wire-contract не изменялись.

Software evidence: package-source commit `b11a92d8925870d29b3b2bc22553d87730053810`, Actions run `33387188959`; APK SHA-256 `3a904f1097ae28bbe3ca377f4b5917022fa048d39ee5240f17ee268441b220b6`; firmware `.ino` SHA-256 `0a4240795749dd71917d0314d57506480bf9b3240deed68dd04228bbce0c6d4e`; combined artifact ZIP SHA-256 `2e132aecb7027d956a0beb09464a6e28bdc48d59b888b16eb7f0e256240306f6`.

Статус: **NATIVE TESTED / CI verified**. Для повышения до `HARDWARE TESTED` нужен отдельный A/B runtime-прогон ESP32-S3 с E22 подключённым и отключённым.

## Operator UX

- Удалены технические протокольные термины из пользовательских экранов Commander и OLED.
- VANGUARD оставлен как публичное название протокола.
- `Состав сети` заменён на `Список узлов` там, где это понятнее оператору.
- `Самодиагностика` заменена на `Проверка исправности`.
- Технические `Field Test / CSV / JSON` убраны из основных пользовательских подписей.
- События искусственного ухудшения связи теперь пишутся человеческим языком.
- Radar trusted-state переименован в понятное `Знакомое устройство`.
- На схеме сети скрыты сырые идентификаторы/оценки там, где достаточно понятного качества связи.

## OLED

- `КРИПТО` → `ЗАЩИТА`.
- `TX ОЧЕРЕДЬ` → состояние передачи.
- `ДИАГНОСТИКА` → `ПРОВЕРКА`.
- Убраны неработающие `Planned` пункты из операторских меню.
- Оставлены только реально доступные функции.

## Regression protection

Добавлен `tests/operator_vocabulary_check.py`, который запрещает возврат внутренних терминов в операторский UI/OLED и контролирует отсутствие roadmap-пунктов в рабочем меню.

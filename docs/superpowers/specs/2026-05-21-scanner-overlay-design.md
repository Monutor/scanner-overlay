# Scanner Overlay — Design Spec

## Overview

Мобильное Android-приложение для автоматизации ввода штрихкодов в веб-приложение SEW (Складской Электронный Учёт). Работает как overlay поверх SEW, эмулируя поведение ТСД (терминала сбора данных): сканирование штрихкода → автоматический ввод + Enter.

Никакого доступа к API SEW нет. Взаимодействие только через эмуляцию ввода (Accessibility Service).

**Платформа:** Android (Kotlin + Jetpack Compose). iOS — позже.

## User Scenario

1. Пользователь запускает приложение (фоновый сервис)
2. Заходит в SEW, открывает задание
3. Нажимает поле ручного ввода в SEW
4. В notification-шторке нажимает «Сканировать»
5. Открывается overlay-окно с камерой
6. Сканирует штрихкод полки/товара
7. Приложение автоматически вводит штрихкод + Enter в активное поле SEW
8. Overlay закрывается
9. Повтор для следующего товара

## Architecture

Один модуль `app` (MVP), при необходимости разбивается на мультимодуль.

```
com.scanner.overlay/
├── service/              # ScannerForegroundService + Notification
├── accessibility/        # ScannerAccessibilityService (эмуляция ввода)
├── scanner/              # ML Kit Barcode Scanning
├── overlay/              # OverlayActivity (System Alert Window)
├── settings/             # Compose-экран настроек
├── di/                   # Hilt DI
└── util/                 # Утилиты
```

## Components

### ScannerForegroundService

- Тип: `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
- NotificationChannel: `scanner_channel`, importance HIGH
- Notification: иконка + «Сканер готов» + action «Сканировать»
- Action → PendingIntent на `OverlayActivity`
- Запускается при старте приложения или вручную из настроек

### ScannerAccessibilityService

- `BIND_ACCESSIBILITY_SERVICE` с `canRetrieveWindowContent`, `canPerformGestures`
- Слушает `TYPE_WINDOW_STATE_CHANGED`
- Детектит SEW по имени пакета (настраивается)
- Методы эмуляции ввода (в порядке приоритета):
  1. `ACTION_SET_TEXT` + `ACTION_IME_ENTER`
  2. `dispatchGesture` симуляция клавиатуры
  3. Clipboard + `ACTION_PASTE` + Enter

### OverlayActivity

- Тема `.Transparent`, `setShowWhenLocked(true)`
- `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`
- Запрос `SYSTEM_ALERT_WINDOW` при старте
- CameraX preview + ML Kit BarcodeScanner
- Возможность ручного ввода (резерв)
- Кнопка закрытия, обратный отсчёт

### Scanner (ML Kit)

- CameraX `ImageAnalysis.Analyzer`
- BarcodeScanner с `FORMAT_ALL_FORMATS`
- При детекте: вибро/звук → показать → эмуляция ввода → закрыть overlay

### Settings Screen (Jetpack Compose)

- Toggle «Служба запущена»
- Поле «Имя пакета SEW»
- Toggle «Звук при сканировании»
- Кнопка «Проверить разрешения» (камера, overlay, accessibility)
- Статус: сервис, детект SEW

## Data Flow

```text
[Пользователь в SEW] → [Foreground Service: notification active]
                              ↓ (нажатие «Сканировать»)
[OverlayActivity: камера + ML Kit] → [штрихкод найден]
                              ↓
[ScannerAccessibilityService: эмуляция ввода]
                              ↓
[SEW: штрихкод + Enter в поле] → [Overlay закрывается]
```

## Permissions

- `CAMERA` — для сканирования
- `SYSTEM_ALERT_WINDOW` — для overlay поверх приложений
- `BIND_ACCESSIBILITY_SERVICE` — для эмуляции ввода
- `FOREGROUND_SERVICE_SPECIAL_USE` — для Android 14+
- `POST_NOTIFICATIONS` — для Android 13+
- `VIBRATE` — для тактильного отклика

## Dependencies

- Jetpack Compose + Material3 (UI)
- Hilt (DI)
- CameraX (камера)
- ML Kit Barcode Scanning (распознавание)
- Kotlin Coroutines

## Error Handling

| Ситуация | Действие |
|----------|----------|
| Нет камеры | Toast «Камера недоступна» |
| Нет разрешения SYSTEM_ALERT_WINDOW | Диалог → настройки |
| Нет разрешения CAMERA | Диалог → настройки |
| Accessibility Service не активен | Toast «Включите Accessibility Service в настройках» |
| Штрихкод не найден за 30 сек | Таймаут → предложить ручной ввод |
| Ошибка эмуляции ввода | Уведомление «Не удалось вставить текст» |

## Testing Strategy

- **Юнит-тесты:** логика парсинга штрихкода, утилиты
- **Интеграционные:** CameraX + ML Kit на эмуляторе
- **Ручные:** реальное устройство с SEW (полный сценарий)
- **Accessibility Service:** только на реальном устройстве

## Future (Post-MVP)

- Поддержка NFC
- История сканированных штрихкодов
- Экспорт данных
- iOS-версия (кастомная клавиатура)
- Ручной ввод в overlay (если камера не читает)

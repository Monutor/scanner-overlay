# ScannerOverlay — архитектурные рекомендации (2026-08)

## 1. Сканер
**Сейчас:** CameraX + MLKitBarcodeScanning — ок для универсальности.
**Если целиться в Zebra TC5x/TC2x:** заменить на `com.zebra.sdk.reader` — быстрее, работает без камеры (лазер напрямую), есть `OnScanListener`.

## 2. Overlay UI ✅ СДЕЛАНО (v1.19)
**Было:** native View (`FloatingPanel`) — LinearLayout, ImageView, TextView, GradientDrawable.
**Стало:** Compose через `ComposeView` + `WindowManager`. Анимация slide через `animateDpAsState`, иконки Material Icons, стиль на Modifiers. Public API не изменился — `ScannerForegroundService` переписывать не нужно.

## 3. SEW Injection (главная боль)
**Сейчас:** gesture-тапы по координатам → ожидание модалки → poll поля → заполнение. Хрупко: ломается при изменении layout SEW, зависит от DPI/размера экрана.
**Как лучше:** clipboard paste без gesture-тапов:
1. Скопировать штрих в буфер (`ClipboardManager.setPrimaryClip`)
2. Найти сфокусированное поле через `findFocus(FOCUS_INPUT)`
3. Эмулировать Ctrl+V через `ACTION_PASTE` или `AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE`
4. Эмулировать Enter через `IME_ACTION_DONE`
Работает на любом экране, не зависит от координат.

## 4. Overlay — foreground service vs activity
**Сейчас:** `OverlayActivity` стартуется из notification, перекрывает SEW полностью. При сворачивании теряется фокус.
**Как лучше:** overlay как `foreground service` с `WindowManager` + `ComposeView`. Живёт параллельно SEW, не блокирует экран.

## 5. База данных (shelves/products)
**Сейчас:** GitHub releases (`Monutor/scanner-overlay`, `Monutor/DataBaseProducts`). Бесплатно, но медленно и с rate limits.
**Как лучше для production:** Firebase Realtime DB или Supabase + кэш в SharedPreferences.

## 6. Калибровка
**Сейчас:** ручная — пользователь тыкает по координатам двух точек.
**Как лучше:** автоматическая через accessibility tree — найти кнопку «Ручной ввод» и кнопку «Готово» в DOM, запомнить реальные bounds. Не зависит от экрана.

## 7. Логирование
**Сейчас:** только `Log.d` (`BuildConfig.DEBUG`). На устройстве пользователя бесполезно для отладки.
**Как лучше:** писать лог в файл (SD Card или internal storage), экспортировать через share/intent.

---

## Итого: стек при переделке с нуля
- Kotlin + Compose (весь UI) + CameraX + MLKit + Hilt
- Overlay: `WindowManager` + `ComposeView` поверх SEW
- Injection: clipboard paste без gesture-тапов
- Бэкенд: Supabase/Firebase вместо GitHub
- Калибровка: автоматическая через accessibility tree

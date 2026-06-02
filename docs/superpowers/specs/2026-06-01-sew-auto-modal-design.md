# Авто-ввод штрихкода в модалку SEW (Chrome WebView)

**Дата:** 2026-06-01
**Статус:** дизайн согласован, ожидает финального ревью пользователя
**Целевая версия:** следующий релиз после текущего `HEAD`

---

## 1. Контекст и мотивация

SEW — веб-приложение для складских операций, открывается в Chrome на Android. При выполнении задания (например, перенос товара из торгового зала на склад) пользователь сначала сканирует штрих полки, а затем штрих товара. В мобильной версии SEW вместо ТСД-сканера используется кнопка «Ручной ввод», открывающая модалку с текстовым полем «Штрих-код» и кнопками «Отмена» / «Готово».

Текущий `Scanner Overlay` после скана вставляет штрих в первый попавшийся `editable`/`focused` input на экране. Это означает, что пользователь **обязан** сначала тапнуть кнопку «Ручной ввод» в SEW, и только потом запускать сканер. Двойное действие замедляет работу.

Цель: после нажатия плавающей кнопки / уведомления и успешного скана — **полностью автоматически** открыть модалку «Ручной ввод» в SEW, ввести туда штрих и подтвердить кнопкой «Готово». Без промежуточных тапов пользователя.

## 2. Текущее поведение

- `ScannerAccessibilityService.autoInjectText(barcode)` ищет `findFocus(FOCUS_INPUT)` или первый editable input во всех окнах.
- Вставка: `ACTION_SET_TEXT` → fallback на clipboard + long-click + paste через контекстное меню.
- После вставки: `ACTION_IME_ENTER` (API 33+) с fallback на `ACTION_CLICK`, затем через 400мс `findAndClickSendButton(2000, barcode)` ищет «Send»/«Отправить»/«Submit»/«Готово»/«Done» и кликает.
- В `OverlayActivity.finishRunnable` через 3 сек после успешного скана → `autoInjectText` → `injectText` → `finish()`.
- Фильтрации по `packageName` нет.

## 3. Целевое поведение

1. Юзер в Chrome открывает SEW, доходит до места, где нужен штрих полки/товара.
2. Тапает плавающую кнопку или уведомление → открывается `OverlayActivity` (камера).
3. Наводит камеру на штрих → 200мс вибро + beep + зелёная рамка + текст «штрихкод найден».
4. Через 2 сек `OverlayActivity` **запускает цепочку в `ScannerAccessibilityService`** и закрывается (Chrome получает фокус).
5. Accessibility-сервис выполняет цепочку (см. раздел 5) за ≤ 4 сек.
6. По завершении:
   - **успех** — короткий тост «Штрих введён» + вибро 100мс.
   - **ошибка** — тост «Не удалось ввести — откройте модалку вручную» + вибро ошибки 400мс.
7. Плавающая кнопка снова доступна.

**Отмена пользователем** во время цепочки accessibility — не поддерживается (см. edge cases).

## 4. Архитектура изменений

### 4.1 Новые компоненты

| Файл | Назначение |
|---|---|
| `calibration/SewCalibration.kt` | `data class SewCalibration(targetPackage: String, openModal: Point, input: Point, confirm: Point)` + `isCalibrated` (true если `targetPackage.isNotEmpty()`). |
| `calibration/SewCalibrationActivity.kt` | Пошаговый fullscreen overlay для записи 3 точек |
| `calibration/SewCalibrationViewModel.kt` | Состояние шага калибровки + сохранение в prefs |
| `settings/SewTestResult.kt` | `data class` + `data class StepStatus(name, ok, message)` для отображения результата теста |

### 4.2 Изменения существующих

| Файл | Что меняется |
|---|---|
| `accessibility/ScannerAccessibilityService.kt` | + метод `runSewAutoInput(barcode, calibration, testMode: Boolean = false, onResult: SewInputCallback)`; + `setOnInjectionResultListener`; + `cancelOngoingSewInput()` (отмена из тестового режима). Сигнатура callback: `typealias SewInputCallback = (success: Boolean, message: String) -> Unit`. |
| `overlay/OverlayActivity.kt` | + новое UI-состояние «Ввод в SEW…» (2 сек перед запуском accessibility); переход на accessibility-цепочку вместо текущей 3-сек `finishRunnable`; снятие `FLAG_NOT_TOUCHABLE` не нужно — оверлей закрывается до кликов. |
| `settings/SettingsViewModel.kt` | + `sewCalibration: StateFlow<SewCalibration>`; + `saveSewCalibration(...)`; + `runSewCalibrationTest()` (suspend). |
| `settings/SettingsScreen.kt` | + новая карточка «Калибровка SEW» со статусом и кнопками «Откалибровать» / «Тест». |
| `di/AppModule.kt` | + `@Provides fun provideSewCalibration(prefs): SewCalibration` (тонкая обёртка над prefs). |

### 4.3 Хранение данных (`scanner_prefs`)

| Key | Type | Default | Назначение |
|---|---|---|---|
| `sew_calibrated` | Boolean | `false` | прошла ли калибровка (фактически дублирует `targetPackage.isNotEmpty()`) |
| `sew_target_package` | String | `""` | `packageName` приложения, в котором проходила калибровка (Chrome `com.android.chrome` ИЛИ PWA WebAPK `org.chromium.webapk.<hash>`) |
| `sew_open_modal_x` | Int | — | X кнопки «Ручной ввод» |
| `sew_open_modal_y` | Int | — | Y кнопки «Ручной ввод» |
| `sew_input_x` | Int | — | X поля ввода (fallback) |
| `sew_input_y` | Int | — | Y поля ввода (fallback) |
| `sew_confirm_x` | Int | — | X кнопки «Готово» |
| `sew_confirm_y` | Int | — | Y кнопки «Готово» |

**`packageName` хранится в prefs**, не в коде. Определяется автоматически при первом тапе калибровки (см. раздел 7.1). Это поддерживает и обычный Chrome (`com.android.chrome`), и PWA WebAPK (собственный `packageName` вида `org.chromium.webapk.<hash>` или `com.google.sites.app…`). Если юзер когда-нибудь захочет поддержать Яндекс.Браузер — добавит ещё одну калибровку (см. раздел 9).

### 4.4 AndroidManifest

Без изменений: `BIND_ACCESSIBILITY_SERVICE`, `SYSTEM_ALERT_WINDOW`, `INTERNET`, `VIBRATE` — всё уже объявлено. Калибровочная активити — обычный `Activity` без `exported=true` (запускается только из нашего SettingsScreen через явный Intent).

## 5. Пошаговый data flow

### 5.1 Инициация (OverlayActivity)

```
[1] Скан успешен → OverlayViewModel.state = Success
        ↓ (через 2 сек, существующая задержка)
[2] OverlayActivity запускает новый метод triggerSewAutoInput(barcode):
       - Проверяет calibration.isCalibrated (через SettingsViewModel.di).
       - Если false → fallback на старую логику (autoInjectText в текущий focused
         input) + Toast «Сделайте калибровку SEW» + return.
       - Если true → ScannerAccessibilityService.instance
           .runSewAutoInput(barcode, calibration, onResult = ::onSewInputResult).
        ↓
[3] OverlayActivity.finish() — Chrome получает фокус
        ↓ (async)
[4] Accessibility-сервис выполняет цепочку (5.2)
        ↓
[5] onResult(success: Boolean) → Toast + Vibrator (вибро 100мс на успех,
                                    400мс на ошибку)
```

`OverlayActivity.finishRunnable` заменяется: вся 3-сек пауза теперь уходит на новое UI-состояние «Ввод в SEW…» (тот же «штрих найден» overlay, текст «Ввод в SEW…»); за 100мс до `finish()` запускается `runSewAutoInput`.

### 5.2 Цепочка `runSewAutoInput` (ScannerAccessibilityService)

**Параметр `testMode: Boolean`** — при `true`:
- Используется фейковый штрих `"TEST_CALIBRATION"` (игнорируется переданный `barcode`).
- После шага 4 (ввод) переходим не на шаг 5 (проверка содержимого), а сразу к шагу 6 в режиме «только проверить наличие»: `findAccessibilityNodeInfosByText("Готово")` — если нашли clickable → `onResult(true)`, иначе → `onResult(false, "Кнопка «Готово» не найдена")`. Клика не делаем.
- На шаге 5 в обычном режиме проверка `current.contains(barcode)` пропускается в testMode.

**Watchdog:** в начале `runSewAutoInput` ставит `mainHandler.postDelayed(4000) { if (running) onResult(false, "Таймаут") }`. На каждом успешном шаге `removeCallbacks` ставится заново (rolling timeout), на каждом `onResult(...)` — `removeCallbacks` окончательно.

```
[1] Найти целевое окно:
    val target = windows.firstOrNull {
        it.root?.packageName == calibration.targetPackage && it.isActive
    }
    ├─ null → return onResult(false, "SEW не на переднем плане — откройте PWA/Chrome")
    └─ OK → перезапустить watchdog
        ↓
[2] Клик по кнопке «Ручной ввод»:
    dispatchGesture(GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(
            calibration.openModalPoint, 0, 50))
        .build(), null, null)
    mainHandler.postDelayed(600) { step3() }
        ↓
[3] step3() — найти input в модалке (приоритеты):
    a) findFocus(FOCUS_INPUT) в окне Chrome → если editable → step4(input)
    b) findAccessibilityNodeInfosByText("Штрих-код")
       → фильтр isEditable → первый → step4(input)
    c) если a и b не нашли → dispatchGesture по calibration.inputPoint
       → postDelayed(300) → findFocus повторно → если editable → step4(input)
    d) иначе → return onResult(false, "Поле ввода не найдено — перекалибруйте")
        ↓
[4] step4(inputNode) — ввод штриха:
    a) ACTION_SET_TEXT(barcode) на inputNode
    b) fallback (если через 200мс проверка показала, что в поле не наш штрих):
       clipboard.setPrimaryClip → dispatchGesture по центру inputNode
       (имитация KEYCODE_PASTE через GestureDescription + tap+key не работает —
       в WebView paste делается через long-click → контекстное меню → «Вставить»;
       в WebView обычно достаточно ACTION_SET_TEXT, fallback оставлен на крайний случай)
    c) если и fallback не сработал → return onResult(false, "Ввод не зафиксирован")
        ↓ (200мс)
[5] Проверить, что в поле наш штрих:
    val current = findFocus(FOCUS_INPUT)?.text?.toString() ?: ""
    ├─ !current.contains(barcode) → return onResult(false, "Ввод не зафиксирован")
    └─ OK → перезапустить watchdog
        ↓
[6] Кликнуть «Готово»:
    a) findAccessibilityNodeInfosByText("Готово") → первый clickable
       → ACTION_CLICK
    b) fallback: dispatchGesture по calibration.confirmPoint
    c) если кнопка найдена, но не clickable → onResult(false, "Кнопка «Готово» заблокирована")
        ↓
[7] onResult(true) → убрать watchdog
```

**Жёсткий таймаут цепочки:** 4 сек (rolling). Каждый успешный перезапускает watchdog на 4 сек вперёд.

**Потокобезопасность:** `runSewAutoInput` ставит флаг `sewInputInProgress = true` на время цепочки. Повторный вызов (юзер нажал плавающую кнопку во время ввода) — отвергается, юзер увидит тост «Подождите завершения ввода».

### 5.3 Тест калибровки (Settings → «Тест»)

Запускает цепочку `runSewAutoInput` с фейковым штрихом `"TEST_CALIBRATION"`, **но не подтверждает ввод**:
- Шаги 1–4 те же.
- После шага 4 — НЕ кликает «Готово» (только проверяет, что кнопка найдена).
- Возвращает `SewTestResult(steps: List<StepStatus>)` в VM.

UI в SettingsScreen показывает пройденные шаги: `✓ Chrome найден`, `✓ Кнопка «Ручной ввод» доступна`, `✓ Поле ввода найдено`, `✓ Ввод работает`, `✓ Кнопка «Готово» найдена` (или `✗` с описанием).

## 6. Edge cases и обработка ошибок

| Случай | Поведение | Где обрабатывается |
|---|---|---|
| Юзер не в SEW (Chrome или PWA не на переднем плане) | `onResult(false, "Откройте SEW (PWA или Chrome)")` → тост, вибро-ошибка | Шаг 5.2 [1] |
| Модалка не открылась за 1.2 сек после клика (визуально нет диалога) | 1 повторный клик; затем `onResult(false, "Модалка не открылась — перекалибруйте")` | Шаг 5.2 [2] |
| `ACTION_SET_TEXT` не сработал в WebView input | Clipboard + KEYCODE_PASTE | Шаг 5.2 [4] |
| `findFocus` + `findAccessibilityNodeInfosByText` не нашли input | Fallback на координаты `input_x/y` | Шаг 5.2 [3c] |
| В поле не наш штрих после ввода | `onResult(false, "Ввод не зафиксирован")` | Шаг 5.2 [5] |
| Кнопка «Готово» не найдена accessibility-поиском | Fallback на координаты `confirm_x/y` | Шаг 5.2 [6b] |
| Кнопка «Готово» найдена, но не clickable | `onResult(false)` | Шаг 5.2 [6a] |
| Юзер нажал блокировку экрана во время цепочки | `windows` станут недоступны → `onResult(false)` | Все шаги |
| Несколько Chrome-окон (юзер открыл несколько вкладок) | `firstOrNull { it.isActive }` — обычно одно | Шаг 5.2 [1] |
| Юзер нажал «Отмена» в модалке SEW вручную | Модалка закрылась → input не найден → `onResult(false, "Модалка закрыта")` | Шаг 5.2 [3] |
| Цепочка превысила 4 сек | `onResult(false, "Таймаут")` | Watchdog-Handler в начале [1] |
| Калибровка не пройдена (юзер новый) | До цепочки проверка `calibration.isCalibrated`; если false → fallback на старую логику (`autoInjectText` в текущий focused input) + тост «Сделайте калибровку SEW» | OverlayActivity до вызова runSewAutoInput |
| SEW обновился, тексты модалки изменились | «Штрих-код» / «Готово» — стандартные Material-тексты, вряд ли изменятся. Если изменятся → кнопка «Тест» в настройках покажет провал → юзер перекалибрует (координаты модалки) | Ручная перекалибровка |

## 7. Калибровка (`SewCalibrationActivity`)

**Тема:** `Theme.ScannerOverlay.Transparent` (полупрозрачный overlay, как `OverlayActivity`).
**Window flags:** `NOT_TOUCH_MODAL` + `WATCH_OUTSIDE_TOUCH` + `KEEP_SCREEN_ON`. `FLAG_NOT_TOUCHABLE` **не выставляем** — тапы пользователя должны доходить до Chrome/SEW.
**Манифест:** `screenOrientation="portrait"`, `excludeFromRecents="true"`, `taskAffinity=""`, `exported="false"`, `showWhenLocked="true"`, `turnScreenOn="true"`.

### 7.1 Пошаговый flow

**Перед стартом (внутри активити):** 3 сек обратный отсчёт «Переключитесь в SEW (PWA-приложение или Chrome с открытой страницей)», потом кнопка «Готово, начать».

**Определение `packageName` (в момент первого тапа):**
- На шаге 1, в обработчике тапа, **перед** записью координат читаем `accessibilityService.windows` (если доступен) или `rootInActiveWindow.packageName` — `currentPackageName`.
- Сохраняем `currentPackageName` в `sew_target_package`.
- Это означает, что юзер **обязан** быть в SEW (PWA или Chrome) в момент тапа на шаге 1. Если не в SEW — координаты запишутся для другого приложения и `runSewAutoInput` работать не будет. Митигация: инструкция перед шагом 1 + тест калибровки (раздел 8) сразу покажет, что не находит окно.

**Шаг 1 — точка «Ручной ввод»:**
- Инструкция: «Найдите синюю кнопку с иконкой штрих-кода (без текста) на странице SEW и тапните по ней».
- Корневой view активити рисует полупрозрачную подложку с инструкцией, **но не перехватывает тапы** (`isClickable = false`, `isFocusable = false` на view; `OnTouchListener` НЕ ставим на root).
- Вместо этого: на активити регистрируется `WindowManager.Callback.onTouchEvent` или `MotionEvent`-listener через `decorView.dispatchTouchEvent` — перехватываем тап **только** для записи координат и `packageName`, не блокируя доставку события дальше (`return false` из onTouchEvent).
- Координаты сохраняются как `open_modal_x/y`, `packageName` — как `sew_target_package`.
- Пауза 1 сек → переход к шагу 2.

**Шаг 2 — точка input (fallback):**
- Инструкция: «Откройте модалку (нажмите кнопку «Ручной ввод» ещё раз) и тапните по полю ввода «Штрих-код»».
- Аналогично шагу 1 — сохраняем `input_x/y`.

**Шаг 3 — точка «Готово»:**
- Инструкция: «Тапните по синей кнопке «Готово» в правом нижнем углу модалки».
- Аналогично шагу 1 — сохраняем `confirm_x/y`.

**Сохранение и завершение:**
- `prefs.edit().putBoolean("sew_calibrated", true).putInt("sew_open_modal_x", x1)...apply()`
- `setResult(RESULT_OK)` + `finish()`.
- Возврат в `SettingsScreen`, который перечитывает `sewCalibration` flow и сразу показывает обновлённый статус «Откалибровано».

### 7.2 Поведение «Откалибровать» и «Сбросить»

В `SettingsScreen` две разные кнопки:

| Кнопка | Действие |
|---|---|
| «Откалибровать» | Запускает `SewCalibrationActivity` через `startActivityForResult`. Если юзер проходит все 3 шага → `sew_target_package` (определён на шаге 1) и 6 координат обновляются, `sew_calibrated=true` остаётся. Если юзер нажал back на любом шаге → старые значения **не меняются** (отмена ничего не портит). |
| «Сбросить калибровку» | Стирает `sew_calibrated=false`, `sew_target_package=""` и все 6 координат из prefs. Прячется, если `sew_calibrated == false`. |

## 8. Тест калибровки (кнопка «Тест» в SettingsScreen)

**Запуск:** `SettingsViewModel.runSewCalibrationTest()` (suspend, переключается на Main при вызове `runSewAutoInput`).

**Логика:**
1. Проверить, что `sew_calibrated == true`. Иначе → возврат с ошибкой «Сначала откалибруйте».
2. Запустить `runSewAutoInput(barcode = "", calibration, testMode = true, onResult = { ... })`:
   - Шаги 1–4 (Chrome есть, клик, поиск input, ввод) выполняются.
   - Шаг 5 (проверка содержимого) — пропускается (testMode).
   - Шаг 6 (клик «Готово») — **не выполняется**, только проверяется, что кнопка найдена.
3. По ходу `onResult` пишет StepStatus в `MutableStateFlow<SewTestResult>`.

**Типы (`settings/SewTestResult.kt`):**
```kotlin
data class SewTestResult(
    val steps: List<StepStatus>,
    val inProgress: Boolean = false
)

data class StepStatus(
    val name: String,      // "Chrome найден" / "Кнопка «Ручной ввод» доступна" / ...
    val ok: Boolean,
    val message: String? = null  // null если ok, иначе причина
)
```

**UI:**
- 5 строк-статусов с `✓` / `✗` и пояснением.
- Кнопка «Закрыть» сворачивает результат (но `inProgress` блокирует повторный запуск).

**Модалка после теста:** остаётся открытой с TEST_CALIBRATION в поле. Юзер сам нажимает «Отмена» (или возвращается и нажимает «Готово» — TEST_CALIBRATION не существует в базе, ничего не сломает).

## 9. Будущие расширения (НЕ в скоупе этого спека, но учтено в дизайне)

- **Несколько пакетов одновременно (Chrome + Яндекс.Браузер + ещё один PWA):** заменить единственный `sew_target_package: String` + 6 координат на `Map<String, SewCalibration>` в prefs, ключи префиксовать: `sew_<pkg>_open_modal_x`. UI калибровки добавляет выбор «для какого браузера», в `SettingsScreen` — список откалиброванных приложений. `runSewAutoInput` пробегает по всем ключам `sew_*_open_modal_x` и ищет foreground-окно с совпадающим `packageName`.
- **Несколько приложений (не только SEW):** та же схема с профилями `target_package → calibration`. `SettingsScreen` показывает список откалиброванных приложений.
- **Deep link из SEW** (если SEW когда-нибудь поддержит URL-схему вида `sew://scan?code=XXX`): отдельный модуль, не связан с accessibility. Включается в настройках, приоритет выше калибровки.
- **IME-клавиатура** (подход В из brainstorm): если позже нужно будет сканировать в произвольные приложения, добавляется как отдельный модуль, не задевает текущий.

## 10. Альтернативы, отклонённые

| Подход | Почему отклонён |
|---|---|
| Чистый accessibility-поиск кнопки «Ручной ввод» | У кнопки нет текста, нет ARIA, нет стабильного id — поиск невозможен (см. HTML в brainstorm) |
| Чистая калибровка всех 3 точек без accessibility | Менее надёжно: `findFocus`/`findAccessibilityNodeInfosByText` для input и «Готово» стабильнее координат в Material-диалогах Chrome |
| Custom IME (клавиатура-сканер) | Слишком дорого для одного кейса; не решает «пользователь должен открыть модалку», а только ввод |
| Deep link в SEW | Требует координации с разработчиками SEW; вне нашего скоупа |
| `QUERY_ALL_PACKAGES` + выбор целевого приложения из списка | Избыточно для одного SEW; добавит permission review и UX-стоимость |
| `adb shell input` через root | Не работает на нерутованных устройствах склада |

## 11. Решения по открытым вопросам (из brainstorm)

| Вопрос | Решение |
|---|---|
| Сколько пакетов поддерживать? | Один — определяется автоматически при калибровке. Работает и для Chrome (`com.android.chrome`), и для PWA WebAPK (собственный `packageName`). Поддержка нескольких пакетов одновременно — в будущих расширениях (раздел 9). |
| Только «Готово» или Enter? | Только «Готово». Enter-фолбэк не делаем. |
| Поведение при ошибке? | Тост + вибро-ошибка; модалка остаётся как открыл юзер (или закрылась — зависит от шага, на котором упали). |
| Другие приложения кроме SEW? | Нет, только SEW. |
| Тест калибровки в настройках? | Да, добавляем. |

## 12. Критерии приёмки (для writing-plans)

1. `SewCalibrationActivity` запускается из SettingsScreen, проходит 3 шага, сохраняет координаты в prefs.
2. После калибровки: при скане в Chrome в SEW — модалка открывается, штрих вводится, «Готово» нажимается автоматически, без тапов юзера.
3. Если калибровка не пройдена — fallback на старую логику (вставка в текущий focused input) + тост-предупреждение.
4. Все edge cases из раздела 6 покрыты кодами ошибок и не приводят к ANR/крашу.
5. Кнопка «Тест» в настройках проходит 5 шагов проверки и показывает результат.
6. Существующая логика (ручной ввод, авто-вне-SEW, multi-window floating button) не сломана.
7. `build.ps1 install` ставит APK, на устройстве с активным Chrome и SEW новый сценарий работает end-to-end.

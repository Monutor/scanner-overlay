# Barcode Highlight Overlay Design

**Feature:** Зелёный bounding box вокруг распознанного штрихкода на превью камеры.

**Goal:** Визуально подсветить область штрихкода при его детекции — аналогично тому, как это делают магазинные сканеры.

## Поведение

- Flash-подтверждение: bounding box отображается ~500ms с fade-анимацией
- Показывается однократно в момент детекции, не трекится в реальном времени
- Box исчезает при закрытии OverlayActivity

## Архитектура

### Data flow

```
BarcodeAnalyzer
  ├─ MLKit Barcode.boundingBox (android.graphics.Rect)
  ├─ imageProxy.width/height
  └─ imageProxy.imageInfo.rotationDegrees
       ↓
  BarcodeOverlayData(boundingBox: Rect, imageWidth, imageHeight, imageRotation)
       ↓
  ScannerResult.Success(barcode, format, overlayData)
       ↓
  CameraPreview.onBarcodeDetected → currentOverlayData state
       ↓
  BarcodeHighlightOverlay → Canvas overlay
       ↓
  Rect.toDisplayRect() transform: image coords → display coords
       ↓
  Canvas.drawRoundRect (green fill + stroke)
```

### Coordinate transformation

**Input:** bounding box in image native coords (imgW×imgH), rotation, view size (viewW×viewH)

1. Rotate box by rotation° CW:
   - 90°: `Rect(top, imgW-right, bottom, imgW-left)`
   - 270°: `Rect(imgH-bottom, left, imgH-top, right)`
   - 180°: `Rect(imgW-right, imgH-bottom, imgW-left, imgH-top)`

2. Display dims after rotation:
   - rotation%180==0: dispW=imgW, dispH=imgH
   - else: dispW=imgH, dispH=imgW

3. FILL_CENTER: scale = max(viewW/dispW, viewH/dispH), offset = ((viewW-dispW*scale)/2, (viewH-dispH*scale)/2)

4. Result: screenX = rotatedX\*scale + offsetX

### UI layers

```
Box(fillMaxSize)
  ├── CameraPreview (PreviewView AndroidView)
  ├── BarcodeHighlightOverlay (Canvas — green box)
  ├── Dim scrims (top/bottom)
  ├── Viewfinder + scan line
  ├── Success/Error overlays
  └── Bottom bar (torch + keyboard)
```

## Изменённые файлы

- `scanner/ScannerResult.kt` — data class `BarcodeOverlayData`, опциональное поле в `Success`
- `scanner/BarcodeAnalyzer.kt` — извлечение boundingBox из MLKit, передача в `Success`
- `overlay/OverlayActivity.kt` — `toDisplayRect()`, `BarcodeHighlightOverlay` composable, интеграция в CameraPreview/OverlayContent

## Анимация

- `animateFloatAsState` alpha 0→1 за 300ms при появлении
- Длительность показа: 500ms
- Исчезает вместе с закрытием Activity (по существующей логике 300ms после детекции)

## Edge cases

- Если boundingBox == null (MLKit не вернул) — просто не рисуется рамка, без ошибок
- Если координаты за пределами view — Canvas естественно обрезает рисование
- Если viewWidth/viewHeight == 0 (Compose ещё не измерил) — Canvas не рисуется

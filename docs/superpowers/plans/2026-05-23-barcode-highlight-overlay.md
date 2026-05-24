# Barcode Highlight Overlay — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add green bounding box overlay around detected barcode in camera preview.

**Architecture:** Compose Canvas overlay on top of PreviewView. MLKit returns barcode bounding box → transform image coords to display coords → draw green roundRect with fade animation.

**Tech Stack:** Kotlin, Jetpack Compose, CameraX, MLKit Barcode Scanning

---

### Task 1: ScannerResult.kt — add BarcodeOverlayData

**Files:**
- Modify: `app/src/main/java/com/scanner/overlay/scanner/ScannerResult.kt`

- [ ] **Step 1: Add data class and optional field**

```kotlin
package com.scanner.overlay.scanner

import androidx.compose.ui.geometry.Rect

data class BarcodeOverlayData(
    val boundingBox: Rect,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageRotation: Int
)

sealed interface ScannerResult {
    data class Success(
        val barcode: String,
        val format: Int,
        val overlayData: BarcodeOverlayData? = null
    ) : ScannerResult
    data class Error(val message: String) : ScannerResult
    data object Scanning : ScannerResult
}
```

---

### Task 2: BarcodeAnalyzer.kt — extract boundingBox from MLKit

**Files:**
- Modify: `app/src/main/java/com/scanner/overlay/scanner/BarcodeAnalyzer.kt`

- [ ] **Step 1: Add Rect import**

```kotlin
import androidx.compose.ui.geometry.Rect
```

- [ ] **Step 2: Extract bounding box and pass in Success**

Replace `onResult(ScannerResult.Success(value, barcode.format))` with:

```kotlin
val overlayData = barcode.boundingBox?.let { box ->
    BarcodeOverlayData(
        boundingBox = Rect(
            box.left.toFloat(), box.top.toFloat(),
            box.right.toFloat(), box.bottom.toFloat()
        ),
        imageWidth = imageProxy.width,
        imageHeight = imageProxy.height,
        imageRotation = imageProxy.imageInfo.rotationDegrees
    )
}
onResult(ScannerResult.Success(value, barcode.format, overlayData))
```

---

### Task 3: OverlayActivity.kt — Canvas overlay

**Files:**
- Modify: `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt`

- [ ] **Step 1: Add imports**

```kotlin
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize
import com.scanner.overlay.scanner.BarcodeOverlayData
```

- [ ] **Step 2: Add coordinate transform and overlay composable**

```kotlin
private fun Rect.toDisplayRect(
    imgW: Int, imgH: Int, rotation: Int,
    viewW: Float, viewH: Float
): Rect {
    val (rLeft, rTop, rRight, rBottom) = when (rotation) {
        90 -> {
            val w = imgW.toFloat()
            floatArrayOf(top, w - right, bottom, w - left)
        }
        270 -> {
            val h = imgH.toFloat()
            floatArrayOf(h - bottom, left, h - top, right)
        }
        180 -> {
            val w = imgW.toFloat(); val h = imgH.toFloat()
            floatArrayOf(w - right, h - bottom, w - left, h - top)
        }
        else -> floatArrayOf(left, top, right, bottom)
    }

    val dispW = if (rotation % 180 == 0) imgW.toFloat() else imgH.toFloat()
    val dispH = if (rotation % 180 == 0) imgH.toFloat() else imgW.toFloat()
    val scale = maxOf(viewW / dispW, viewH / dispH)
    val offX = (viewW - dispW * scale) / 2f
    val offY = (viewH - dispH * scale) / 2f

    return Rect(
        rLeft * scale + offX, rTop * scale + offY,
        rRight * scale + offX, rBottom * scale + offY
    )
}

@Composable
private fun BarcodeHighlightOverlay(
    overlayData: BarcodeOverlayData?,
    viewWidth: Float,
    viewHeight: Float
) {
    var show by remember { mutableStateOf(false) }

    LaunchedEffect(overlayData) {
        if (overlayData != null) {
            show = true
            delay(500)
            show = false
        }
    }

    if (show && overlayData != null && viewWidth > 0f && viewHeight > 0f) {
        val displayRect = remember(overlayData, viewWidth, viewHeight) {
            overlayData.boundingBox.toDisplayRect(
                overlayData.imageWidth, overlayData.imageHeight,
                overlayData.imageRotation, viewWidth, viewHeight
            )
        }
        val alpha by animateFloatAsState(
            targetValue = if (show) 1f else 0f,
            animationSpec = tween(300)
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                color = Color(0x334CAF50).copy(alpha = alpha * 0.5f),
                topLeft = Offset(displayRect.left, displayRect.top),
                size = Size(displayRect.width, displayRect.height),
                cornerRadius = CornerRadius(12f)
            )
            drawRoundRect(
                color = Color(0xFF4CAF50).copy(alpha = alpha),
                topLeft = Offset(displayRect.left, displayRect.top),
                size = Size(displayRect.width, displayRect.height),
                cornerRadius = CornerRadius(12f),
                style = Stroke(width = 3f)
            )
        }
    }
}
```

- [ ] **Step 3: Add onBarcodeDetected callback to CameraPreview**

Enhance CameraPreview signature:
```kotlin
fun CameraPreview(
    torchOn: Boolean = false,
    onBarcodeScanned: (String) -> Unit,
    onBarcodeDetected: ((ScannerResult.Success) -> Unit)? = null,
    onShowManualInput: ((String) -> Unit)? = null,
    onCancelFinish: () -> Unit = {},
    modifier: Modifier = Modifier,
    onPreviewViewSize: (IntSize) -> Unit = {}
)
```

Add state and callback:
```kotlin
val currentOnBarcodeDetected = rememberUpdatedState(onBarcodeDetected)
```

Modify analyzer handler:
```kotlin
if (result is ScannerResult.Success && !scanCompleted) {
    scanCompleted = true
    currentOnBarcodeDetected.value?.invoke(result)
    onBarcodeScanned(result.barcode)
}
```

Add onGloballyPositioned to AndroidView:
```kotlin
modifier = modifier.onGloballyPositioned { coordinates ->
    onPreviewViewSize(coordinates.size)
}
```

- [ ] **Step 4: Integrate in OverlayContent**

Add state variables:
```kotlin
var currentOverlayData by remember { mutableStateOf<BarcodeOverlayData?>(null) }
var previewViewSize by remember { mutableStateOf(IntSize.Zero) }
```

Pass callbacks to CameraPreview:
```kotlin
onBarcodeDetected = { result ->
    currentOverlayData = result.overlayData
},
onPreviewViewSize = { previewViewSize = it }
```

Place overlay in Box:
```kotlin
BarcodeHighlightOverlay(
    overlayData = currentOverlayData,
    viewWidth = previewViewSize.width.toFloat(),
    viewHeight = previewViewSize.height.toFloat()
)
```

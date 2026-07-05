# Barcode Database Update Design

**Feature:** Download and merge updated product barcodes into `ArticleBarcodeDatabase` from a remote CSV.

**Goal:** Allow the user to update `barcode-products.csv` data on-device without reinstalling the APK.

---

## Remote Source

CSV hosted as GitHub Release asset, same pattern as `update.json`:

```
https://github.com/Monutor/scanner-overlay/releases/latest/download/barcode-products.csv
```

Same semicolon-delimited format as the existing `barcode-products.csv` in assets.

---

## Storage

- **Base data** — remains in `assets/barcode-products.csv` (shipped with APK).
- **Extra data** — persisted to `context.filesDir/barcode_extra.csv` in the same semicolon format.
- On `init()`, `ArticleBarcodeDatabase` loads assets first, then appends extras.

---

## Merge Logic

1. Download remote CSV.
2. Parse rows into `ProductItem`s, dedup by `articleCode` within the file.
3. Compare each item's `articleCode` against the in-memory set of already-known codes.
4. If `articleCode` is new → add to "new items" list.
5. If already known → skip.
6. Return new items to the UI layer.

---

## UI Flow

Settings screen, section "Система", after `UpdateCard`:

1. **Button** "Загрузить базу ШК товаров" → calls `viewModel.downloadBarcodeDb()`.
2. **Spinner** state — `CircularProgressIndicator` while downloading + parsing.
3. **UpToDate** — toast "База актуальна".
4. **Ready(newItems)** — `AlertDialog` showing each new item (`articleCode` + `name`), scrollable list, button "Добавить".
5. **Error** — toast with error message.

---

## Files Changed

| File | Change |
|---|---|
| `ArticleBarcodeDatabase.kt` | `extrasFile`, `loadExtra()`, `mergeExtra()`, `persistExtra()`; extract `seenArticleCodes` to class field |
| `SettingsViewModel.kt` | `_dbUpdateState`, `downloadBarcodeDb()`, `applyBarcodeDbUpdate()`, `resetBarcodeDbUpdateState()` |
| `SettingsScreen.kt` | `BarcodeDbUpdateCard` composable + dialog; observe `dbUpdateState` |

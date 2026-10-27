# Quarter Master

A pantry and household inventory app. Scan a product's barcode with your phone's camera, and Quarter Master tracks what you have and how fresh it is.

Version 1.0 is an Android app that works entirely on the device. There's no server and no account.

## Features

- **Barcode scanning** with the camera (on-device ML Kit, works offline). Accepts UPC-A, UPC-E, EAN-13 and EAN-8, normalized so the same product always matches. You can also type a code by hand.
- **Products** (the alpha's "UPC Lookup"): name, barcode (optional, for things like fruit), default lifespan, amount and unit, and a photo.
- **Inventory**: every item you own, soonest to expire first, with the alpha's color-coded freshness bar.
- **Best-by dates**: when an item has a best-by date, it overrides the product's default lifespan.
- **Taking items out**: mark them *Used*, *Went bad* or *Discarded*. Everything goes to History and can be put back.
- **Learned lifespans**: best-by dates and spoiled items become lifespan samples. Each product shows how long it really lasts and how often it went bad early, and you can apply the average as its default.
- **Optional online lookup**: scanning something new can prefill the name, size and photo from [OpenFoodFacts](https://openfoodfacts.org). Only the barcode is sent, and this can be turned off in Settings.
- **Backup**: export everything (photos included) to a JSON file, and import it on any device. Imports merge, with the newest version of each entry winning.

## Building

You need the Android SDK and a JDK 17+ (Android Studio's bundled JBR works).

```bash
./gradlew :composeApp:assembleDebug
```

The APK ends up in `composeApp/build/outputs/apk/debug/`. Install it with `adb install -r <apk>` or open the project in Android Studio and press Run.

```bash
./gradlew :composeApp:testDebugUnitTest
```

This runs the unit tests: barcode normalization, freshness and lifespan statistics, the database repository, backup merge rules, and the OpenFoodFacts client against a mock server.

## Project layout

Kotlin Multiplatform plus Compose Multiplatform, so the Windows and Linux ports reuse the same code.

```
composeApp/src/
  commonMain/            Shared by every platform (Android now, desktop next)
    kotlin/.../domain/   Barcode rules, freshness math, lifespan statistics, models
    kotlin/.../data/     SQLDelight repository, backup import/export, OpenFoodFacts client
    kotlin/.../platform/ PlatformServices: what each platform must provide (scanner, files, photos)
    kotlin/.../ui/       All screens (Compose)
    sqldelight/          Database schema and queries
  androidMain/           CameraX + ML Kit scanner, image storage, file pickers, app entry point
  commonTest/            Platform-independent tests
  androidUnitTest/       Database tests against in-memory SQLite
```

### Data model

| Table          | What it holds                                                         | Alpha equivalent |
|----------------|-----------------------------------------------------------------------|------------------|
| `product_type` | A kind of product: barcode (GTIN-14), name, lifespan, amount, photo   | `lookup.txt`     |
| `item`         | One physical item: added date, best-by, removed date and reason        | `log.txt`        |
| `setting`      | Device-local settings (not backed up)                                  | none             |

Every row has a random UUID, `created_at` / `updated_at` timestamps, and a `deleted_at` tombstone instead of being deleted. That's what makes backup merging safe today, and it's what the self-hosted sync server will build on. See [docs/ROADMAP.md](docs/ROADMAP.md).

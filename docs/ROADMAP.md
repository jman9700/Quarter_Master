# Roadmap

## 1.0: Standalone Android app (done)

Fully on-device, with manual entry plus camera barcode scanning. See the README for features.

## 2. Windows, then Linux (Windows done)

A `jvm("desktop")` Compose Desktop target in the same project. It's built and tested on Windows. Linux uses the same code but hasn't been tried on a Linux machine yet. Webcam scanning is still open. The original plan:

1. Add a `jvm("desktop")` target in `composeApp/build.gradle.kts` and a `desktopMain` source set.
2. Implement `PlatformServices` for desktop:
   - **Barcode input**: USB barcode scanners act as keyboards, so the typed-entry dialog already works with them (set `hasBarcodeScanner = false` for that path). Webcam scanning is optional, for example with ZXing plus a webcam library.
   - **Images**: a desktop `ImageStore` writing to the user's app-data folder (`%APPDATA%\QuarterMaster` / `~/.local/share/quartermaster`), using `javax.imageio` to downscale.
   - **Backup files**: native open/save dialogs.
   - **BackHandler**: a no-op.
3. A SQLDelight `JdbcSqliteDriver` pointing at a file in the app-data folder.
4. Packaging with Compose Desktop's `nativeDistributions` (MSI for Windows; DEB/RPM or AppImage for Linux).

Backup files move data between phone and desktop until the server exists.

## 3. Self-hosted sync server

We don't host anyone's inventory. Users run the server themselves (a Docker image on a home server, NAS or Pi-class machine).

Design notes that 1.0 already prepares for:

- **IDs and timestamps**: rows use UUIDs, `updated_at` and `deleted_at` tombstones, so two devices never collide and deletions propagate.
- **Merge rule**: backup import already does a last-write-wins merge per row. Sync is the same merge done continuously over the network, and the backup JSON format is a good starting point for the wire format.
- **Device identity**: each install has a `device_id` (in `setting`).

Open decisions for when we get there:

- Keep last-write-wins rows, or move to an append-only event log (better history, undo and conflict handling at the cost of complexity)?
- Hybrid logical clocks instead of wall-clock `updated_at`, so devices with wrong clocks can't win every conflict.
- Households and members: how to invite someone to a shared inventory, and what roles exist.
- Server stack (Ktor would let the server share the `domain` code and the SQL schema).

## 4. Receipt scanning AI (stretch goal)

To be designed after the server exists. The server is where processing happens and where the shared knowledge (receipt shorthand → product) lives.

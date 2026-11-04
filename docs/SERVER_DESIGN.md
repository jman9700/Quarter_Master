# Quarter Master 2.0: shared inventories and the self-hosted server

Status: approved; Step 1 (server) implemented.

## Goals

- The app manages **multiple inventories**. Each one is either:
  - **Private**: lives only on the device, exactly like today. No server involved.
  - **Shared**: lives on a Quarter Master server that someone hosts themselves. Every member's device keeps a full local copy and syncs it.
- **No central accounts.** Each server keeps its own whitelist of named devices, approved by whoever hosts it. A shared inventory also has a password set by its owner. Entering it on a whitelisted device gives **24 hours** of access, then the app asks again. That's the only daily sign-in.
- **Offline first.** Edits to a shared inventory always succeed locally and wait in a queue until the server is reachable, the same way nutrition lookups wait today.
- **We host nothing.** The server ships as a Docker image (or a plain Java app) that users run on their own hardware.

## Repository layout

```
Client/          The app (Android + Windows/Linux). Everything that was composeApp in 1.x.
Server/          The server: Ktor + SQLite, Dockerfile, docker-compose.yml, deploy poller.
Shared/          Small library both use: sync protocol messages, API paths, barcode rules.
docs/            Design docs and roadmap.
build files      One Gradle build at the root (wrapper, settings, versions) so all three share code.
```

`Shared/` is a third folder, beyond the Client/Server split you asked for. Without it, the client and server would each need their own copy of the protocol definitions, and copies drift. The Gradle wrapper and settings stay at the root because one build drives all three.

## Server

- **Stack:** Kotlin + Ktor, SQLite (one file, easy to back up), runs on Java 21. Uses the same Kotlin libraries as the app.
- **Config (environment variables):** `QM_PORT` (default 47821), `QM_DATA_DIR`, `QM_SERVER_NAME` (shown to clients), `QM_ADMIN_TOKEN` (for the host's admin page), `QM_DISCOVERY` (LAN discovery on/off).
- **Endpoints (all under `/api/v1`):**
  | Method | Path | Who | What |
  |---|---|---|---|
  | GET | `/info` | anyone | Server name, version, git commit, whether discovery is on |
  | POST | `/devices/request` | any device | **Request** to be whitelisted: device id, device secret, and the person's name. Starts as *pending* |
  | GET | `/devices/me` | requesting device | Request status: pending / approved / denied, and the name the host gave it |
  | GET | `/inventories` | whitelisted device | Names and ids of shared inventories, so people can pick one to join (no contents) |
  | POST | `/inventories` | **whitelisted device** | Create a shared inventory with a name and password; returns an **owner key** |
  | POST | `/inventories/{id}/access` | whitelisted device + password | Returns a 24-hour access token, tied to that device |
  | POST | `/inventories/{id}/sync` | access token | Push queued changes, pull everyone else's (one round trip) |
  | PUT/GET | `/inventories/{id}/images/{name}` | access token | Product photos |
  | PUT | `/inventories/{id}/password` | owner key | Change the password. This **revokes every active 24-hour pass** |
  | DELETE | `/inventories/{id}` | owner key | Delete the inventory |
- **Host admin page** at `/admin`, protected by `QM_ADMIN_TOKEN`: review whitelist requests (approve, deny, and set or correct the person's name), see inventories and connected devices, read the **activity log**, revoke a device, and reset a lost owner key. This doubles as the "server monitor".
- **Activity log:** every change the server accepts is recorded with *who* (the whitelisted name), which device, which inventory, *what* (for example "added 2 × Milk", "marked Bananas as went bad", "changed a product's name") and *when*. Password changes, approvals and revocations are logged too. The log can be viewed and filtered on the admin page and is kept in the server database.
- **Security basics:** passwords and secrets stored only as salted PBKDF2 hashes. Failed password attempts slow down per device and IP. The server speaks plain HTTP; for anything beyond your own LAN, put it behind HTTPS (the docs will include a Caddy example like your mc-panel box), because passwords travel in each request that asks for a pass.

## Finding servers

- **LAN:** the app broadcasts a discovery packet (UDP 47821) and lists every server that answers (name, address, version). This works on Wi-Fi and wired networks without configuration.
- **Direct connect:** type a domain name or `ip:port`.

## Whitelist flow: request and approve

Getting onto a server's whitelist is always a **request**, which the host reviews.

1. **Request:** from the app, the person picks a server and taps "Request access". They enter their name (for example "Jordan"). The app sends the request with its device id and a random **device secret**. The secret never leaves the device in plain form again; the server stores only a hash.
2. **Waiting:** the app shows "Waiting for the host to approve this device". The status is checked whenever the app opens or the person taps "Check again". There's no polling.
3. **Review:** the request appears on the admin page as *pending*, with the name and device. The host approves or denies it, and can set or correct the name. That name is what the activity log shows for everything done from that device.
4. **Approved:** the device can now see the server's shared inventories, join them with their passwords, and create new ones. Every change it syncs is logged under that name. The host can revoke a device at any time; revoking also ends its 24-hour passes.

Whitelisting is needed for everything on a server, joining included. Without it, anyone who learned a password could make changes the log couldn't attribute to a real person. Private inventories never involve a server, so they never need any of this.

A person with several devices sends one request per device. The host can give them the same name so the log reads as one person.

## Sync protocol

Built on the backup format from 1.x: whole rows with ids, `updatedAt` and `deletedAt` tombstones.

- **Client side:** every local write to a shared inventory marks the row **dirty**. That set of dirty rows is the outbox queue.
- **When it syncs:** each time the app opens (as with the lookup queue), after edits when a connection is available, and with **Sync now** on the inventories page. No polling.
- **One request does both directions:** the client sends its dirty rows plus its **cursor**, the last server sequence number it has seen. The server applies each row last-write-wins on `updatedAt`, with ties broken by device id. It gives every accepted change a new sequence number and returns all changes after the cursor plus the new cursor. Repeating a request is harmless.
- **Photos:** the sync response lists photos the server doesn't have yet; the client uploads them. Missing photos are downloaded on demand.
- **Offline:** nothing is lost and nothing is blocked. The inventories page and the inventory header show "N changes waiting to sync", the same banner pattern as pending lookups. There's no popup on every edit; that would be noise in a store basement.
- **Expired pass:** queued edits wait. On the next sync attempt the app asks for the password once, then sends everything.
- **Clock caveat:** last-write-wins uses the device's clock, so a phone set hours wrong can win conflicts it shouldn't. Fine for a household; hybrid logical clocks are the upgrade path if it ever matters.

## Client changes

- **New top level: Inventories.** It shows a list with each inventory's kind (private or shared), its server, and the number of changes waiting to sync. From here you can create a private inventory, create a shared inventory (choose a server from the LAN list or connect directly, then set a password), or join a shared inventory (choose a server, request access if this device isn't whitelisted there yet, pick an inventory, enter its password).
- Everything existing becomes your first private inventory, **"My inventory"** (a database migration, as in 1.1).
- **Products belong to an inventory.** Scanning a barcode that another inventory already knows offers to copy that product over.
- **Backups** include all private inventories. A shared inventory's source of truth is its server, but it can still be exported to a file. Older backups import into the current inventory.
- Access tokens, owner keys and device secrets stay in the app's private storage and are **never included in backups**.

## Testing and deploy pipeline

No Playwright. Everything is Kotlin tests, at three levels:

1. **Unit tests** (as today): client on Android and desktop, plus the server.
2. **Integration tests in GitHub Actions:** CI builds the server's Docker image, starts it, and runs the client's sync engine against it, including offline scenarios: queue while unreachable, flush on reconnect, expired pass, conflicting edits from two devices, password change revoking passes.
3. **Staging on the server PC (the `deploy` branch):**
   - When a PR into `main` is opened or updated, CI moves `deploy` to the PR's commit. When a PR merges, `deploy` returns to `main`; when one is abandoned, `deploy` rolls back to `main`.
   - The server PC runs a **poller** (Windows Task Scheduler, every 2 minutes). When `deploy` changes, it builds and restarts the staging server in Docker from a non-OneDrive folder (`C:\QuarterMaster`) and waits until `/api/v1/info` reports that exact commit. It then runs the end-to-end suite against it and reports pass or fail to the PR as a GitHub **status check** ("staging/server-pc").
   - Branch protection on `main` requires that check. Nothing merges until it has passed on real hardware.
   - The server PC needs no open ports and no GitHub runner. It only needs a GitHub token limited to posting commit statuses on this repo, which you create.

## Order of work

1. **Step 1:** the folder restructure, the Shared protocol module, and the server (endpoints, SQLite, whitelist, passes, sync, admin page, LAN discovery, Docker), with server tests.
2. **Step 2:** the client: inventories layer, schema migration, sync engine and queue, server discovery, password entry and 24-hour passes, UI.
3. **Step 3:** integration tests, GitHub Actions workflow, the deploy poller on the server PC, branch protection.

Each step ends with tests passing and a commit. The version bumps to 2.0.0 when the first push happens.

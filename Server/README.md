# Quarter Master server

Host your own server so a household can share inventories. Nobody else hosts your data, and there are no accounts: each server keeps its own whitelist of named devices, and each shared inventory has a password that gives a device 24 hours of access at a time.

See [../docs/SERVER_DESIGN.md](../docs/SERVER_DESIGN.md) for how it works.

## Run it with Docker

From the repository root:

```bash
docker compose -f Server/docker-compose.yml up -d --build
```

The server listens on port **47821**: TCP for the app and the admin page, UDP for LAN discovery. Data (database, photos, admin token) lives in the `quartermaster-data` Docker volume.

Settings go in environment variables or in a `Server/.env` file:

| Variable | Default | What it does |
|---|---|---|
| `QM_SERVER_NAME` | `Quarter Master server` | Name shown in the app's server list |
| `QM_ADMIN_TOKEN` | generated on first start | Password for the admin page. If not set, one is generated into the data folder as `admin-token.txt` |
| `QM_PORT` | `47821` | Port on the host |
| `QM_DISCOVERY` | `on` | `off` to stop answering LAN discovery |

To read a generated admin token:

```bash
docker exec quartermaster cat /data/admin-token.txt
```

## Run it without Docker

You need Java 17 or newer:

```bash
./gradlew -PserverOnly :server:installDist
```

Then run `Server/build/install/quartermaster-server/bin/quartermaster-server` (or `quartermaster-server.bat` on Windows). It keeps its data in `./data`, or in `QM_DATA_DIR` if set.

## The admin page

Open `http://<server>:47821/admin`. The user name can be anything; the password is the admin token. From the admin page you can:

- **Approve or deny whitelist requests.** You can also set the name the activity log uses for each person.
- **Rename or revoke devices.** Revoking a device also ends its 24-hour passes.
- **See inventories and who's signed in right now.**
- **Read the activity log**, for example "Sam: added 2 × Milk".
- **Reset an inventory's owner key** if its owner lost theirs.

The same controls exist as a JSON API under `/api/v1/admin/…`, using the same credentials.

## Finding the server from the app

- **On the same network:** the app lists servers that answer its discovery broadcast. Docker Desktop on Windows and macOS doesn't pass broadcasts into containers, so there you either connect by address, or run the server without Docker for discovery to work. Docker on Linux with `network_mode: host` works too.
- **By address:** enter `192.168.1.20:47821`, or a domain name.

## Reaching it from outside your home: use HTTPS

Inventory passwords travel in the request that asks for a pass. On your own network that's usually fine. Before exposing the server to the internet, put HTTPS in front of it. For example, with [Caddy](https://caddyserver.com) and a domain pointing at your router:

```
pantry.example.com {
    reverse_proxy localhost:47821
}
```

Caddy gets and renews the certificate automatically. Then forward only port 443 on your router, not 47821.

## Backups

Everything is in the data folder or volume: `quartermaster-server.db` (with its `-wal` file) plus `images/`. Stop the server, or copy while it's idle, to get a consistent snapshot.

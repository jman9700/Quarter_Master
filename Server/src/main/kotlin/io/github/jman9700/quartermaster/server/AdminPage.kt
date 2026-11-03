package io.github.jman9700.quartermaster.server

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The host's admin page and server monitor: whitelist requests, devices, inventories, who's
 * signed in, and the activity log. Plain server-rendered HTML; no JavaScript, nothing to build.
 */
object AdminPage {
    private val time = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(ZoneId.systemDefault())

    private fun t(ms: Long?) = ms?.let { time.format(Instant.ofEpochMilli(it)) } ?: "never"

    private fun e(s: String?) = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun render(server: InventoryServer, newOwnerKey: String? = null, newOwnerKeyFor: String? = null): String {
        val info = server.info()
        val devices = server.devices()
        val pending = devices.filter { it.status == "PENDING" }
        val others = devices.filter { it.status != "PENDING" }
        val inventories = server.inventoriesForAdmin()
        val passes = server.activePasses()
        val activity = server.recentActivity()

        return buildString {
            append(
                """<!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta http-equiv="refresh" content="60">
                <title>${e(info.name)} · Quarter Master</title>
                <style>
                  :root { color-scheme: light dark; --bg:#eff1f5; --card:#e6e9ef; --fg:#4c4f69; --muted:#6c6f85; --accent:#1e66f5; --warn:#df8e1d; --bad:#d20f39; }
                  @media (prefers-color-scheme: dark) { :root { --bg:#1e1e2e; --card:#2a2a3e; --fg:#cdd6f4; --muted:#a6adc8; --accent:#89b4fa; --warn:#f9e2af; --bad:#f38ba8; } }
                  body { font: 15px/1.45 system-ui, sans-serif; background: var(--bg); color: var(--fg); margin: 0; padding: 16px; }
                  main { max-width: 980px; margin: 0 auto; }
                  h1 { margin: 0 0 4px; } h2 { margin: 28px 0 8px; font-size: 1.15em; }
                  .muted { color: var(--muted); } .card { background: var(--card); border-radius: 10px; padding: 12px 14px; margin: 8px 0; }
                  table { width: 100%; border-collapse: collapse; } td, th { text-align: left; padding: 6px 8px; vertical-align: top; }
                  tr + tr td { border-top: 1px solid color-mix(in srgb, var(--muted) 25%, transparent); }
                  form { display: inline; } input[type=text] { width: 9em; padding: 4px 6px; }
                  button { padding: 4px 10px; border-radius: 6px; border: 1px solid var(--muted); background: transparent; color: var(--fg); cursor: pointer; }
                  button.primary { background: var(--accent); color: var(--bg); border-color: var(--accent); }
                  .badge { font-size: .8em; padding: 1px 8px; border-radius: 99px; border: 1px solid currentColor; }
                  .PENDING { color: var(--warn); } .APPROVED { color: var(--accent); } .DENIED, .REVOKED { color: var(--bad); }
                  .key { font-family: ui-monospace, monospace; user-select: all; background: var(--bg); padding: 6px; border-radius: 6px; display: inline-block; }
                  .scroll { overflow-x: auto; }
                </style></head><body><main>"""
            )
            append("<h1>${e(info.name)}</h1>")
            append("<p class=\"muted\">Quarter Master server ${e(info.version)} · commit ${e(info.commit.take(12))} · port ${info.port}. This page refreshes every minute.</p>")

            if (newOwnerKey != null) {
                val inv = inventories.firstOrNull { it.id == newOwnerKeyFor }?.name ?: "the inventory"
                append("<div class=\"card\"><b>New owner key for ${e(inv)}</b>, shown only this once. Give it to the owner; they enter it in the app to change the password or delete the inventory.<br><span class=\"key\">${e(newOwnerKey)}</span></div>")
            }

            append("<h2>Requests waiting for you (${pending.size})</h2>")
            if (pending.isEmpty()) append("<p class=\"muted\">Nobody is waiting. New requests from the app show up here.</p>")
            for (d in pending) {
                append("<div class=\"card\"><b>${e(d.person_name)}</b> on ${e(d.device_name)} <span class=\"muted\">· asked ${t(d.requested_at)} · device ${e(d.id.take(8))}</span><br>")
                append("<form method=\"post\" action=\"/admin/devices/${e(d.id)}/approve\">Name in the log: <input type=\"text\" name=\"personName\" value=\"${e(d.person_name)}\"> <button class=\"primary\">Approve</button></form> ")
                append("<form method=\"post\" action=\"/admin/devices/${e(d.id)}/deny\"><button>Deny</button></form></div>")
            }

            append("<h2>Devices (${others.size})</h2><div class=\"card scroll\"><table><tr><th>Person</th><th>Device</th><th>Status</th><th>Last seen</th><th></th></tr>")
            for (d in others) {
                append("<tr><td>${e(d.person_name)}</td><td>${e(d.device_name)}</td><td><span class=\"badge ${d.status}\">${d.status.lowercase()}</span></td><td>${t(d.last_seen_at)}</td><td>")
                append("<form method=\"post\" action=\"/admin/devices/${e(d.id)}/rename\"><input type=\"text\" name=\"personName\" value=\"${e(d.person_name)}\"> <button>Rename</button></form> ")
                if (d.status == "APPROVED") {
                    append("<form method=\"post\" action=\"/admin/devices/${e(d.id)}/revoke\"><button>Revoke</button></form>")
                } else {
                    append("<form method=\"post\" action=\"/admin/devices/${e(d.id)}/approve\"><button>Approve</button></form>")
                }
                append("</td></tr>")
            }
            append("</table></div>")

            append("<h2>Inventories (${inventories.size})</h2><div class=\"card scroll\"><table><tr><th>Name</th><th>Created by</th><th>Products</th><th>Items</th><th></th></tr>")
            for (i in inventories) {
                append("<tr><td>${e(i.name)}</td><td>${e(i.createdBy)} <span class=\"muted\">${t(i.createdAt)}</span></td><td>${i.products}</td><td>${i.items}</td>")
                append("<td><form method=\"post\" action=\"/admin/inventories/${e(i.id)}/reset-owner-key\"><button>Reset owner key</button></form></td></tr>")
            }
            append("</table></div>")

            append("<h2>Signed in now (${passes.size})</h2><div class=\"card scroll\"><table><tr><th>Person</th><th>Device</th><th>Inventory</th><th>Pass ends</th></tr>")
            for (p in passes) append("<tr><td>${e(p.person_name)}</td><td>${e(p.device_name)}</td><td>${e(p.inventory_name)}</td><td>${t(p.expires_at)}</td></tr>")
            append("</table></div>")

            append("<h2>Activity</h2><div class=\"card scroll\"><table><tr><th>When</th><th>Who</th><th>Inventory</th><th>What</th></tr>")
            for (a in activity) append("<tr><td>${t(a.at)}</td><td>${e(a.person_name)}</td><td>${e(a.inventory_name)}</td><td>${e(a.action)}</td></tr>")
            append("</table></div></main></body></html>")
        }
    }
}

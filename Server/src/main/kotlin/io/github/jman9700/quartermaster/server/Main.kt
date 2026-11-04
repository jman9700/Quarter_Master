package io.github.jman9700.quartermaster.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jman9700.quartermaster.server.db.ServerDatabase
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Properties

fun main() {
    val log = LoggerFactory.getLogger("QuarterMaster")
    val config = ServerConfig.fromEnvironment()
    val driver = openDatabase(File(config.dataDir, "quartermaster-server.db"))
    val server = InventoryServer(driver, config)

    val discovery = if (config.discovery) {
        runCatching { DiscoveryResponder(server::info, config.port) }
            .onFailure { log.warn("LAN discovery is off: {}", it.message) }
            .getOrNull()
    } else null

    log.info(
        "{} {} (commit {}) on port {}. Data in {}. Admin page: http://localhost:{}/admin",
        config.serverName, config.version, config.commit, config.port, config.dataDir, config.port,
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        discovery?.close()
        driver.close()
    })
    embeddedServer(Netty, port = config.port) { quarterMasterModule(server) }.start(wait = true)
}

/** Creates the schema on first start and migrates it (by PRAGMA user_version) after that. */
fun openDatabase(file: File): JdbcSqliteDriver {
    val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", Properties().apply { put("foreign_keys", "true") }, ServerDatabase.Schema)
    // WAL lets the admin page read while a sync writes.
    driver.execute(null, "PRAGMA journal_mode=WAL", 0)
    return driver
}

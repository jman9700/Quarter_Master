package io.github.jman9700.quartermaster.server

import io.github.jman9700.quartermaster.protocol.Api
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Properties

/** Everything the host can configure, read from environment variables. */
data class ServerConfig(
    val port: Int = Api.DEFAULT_PORT,
    val dataDir: File,
    val serverName: String,
    val adminToken: String,
    val discovery: Boolean = true,
    val version: String = bundledVersion(),
    /** Set by the Docker build / deploy poller; the deploy pipeline waits for it to match. */
    val commit: String = "dev",
) {
    companion object {
        private val log = LoggerFactory.getLogger(ServerConfig::class.java)

        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            val dataDir = File(env["QM_DATA_DIR"] ?: "data").absoluteFile.apply { mkdirs() }
            return ServerConfig(
                port = env["QM_PORT"]?.toIntOrNull() ?: Api.DEFAULT_PORT,
                dataDir = dataDir,
                serverName = env["QM_SERVER_NAME"]?.takeIf { it.isNotBlank() } ?: "Quarter Master server",
                adminToken = env["QM_ADMIN_TOKEN"]?.takeIf { it.isNotBlank() } ?: storedAdminToken(dataDir),
                discovery = env["QM_DISCOVERY"]?.lowercase() != "off",
                commit = env["QM_COMMIT"]?.takeIf { it.isNotBlank() } ?: "dev",
            )
        }

        /** Without QM_ADMIN_TOKEN, generate one on first start and keep it in the data folder. */
        private fun storedAdminToken(dataDir: File): String {
            val file = File(dataDir, "admin-token.txt")
            if (file.isFile) return file.readText().trim()
            val token = Security.randomToken()
            file.writeText(token)
            log.info("Generated an admin token for the /admin page; it's in {}", file.absolutePath)
            return token
        }

        fun bundledVersion(): String = runCatching {
            Properties().apply {
                ServerConfig::class.java.getResourceAsStream("/quartermaster-server.properties")?.use(::load)
            }.getProperty("version")
        }.getOrNull()?.takeUnless { it.startsWith("$") } ?: "dev"
    }
}

/** Injectable time so tests can expire passes without waiting a day. */
fun interface Clock {
    fun now(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}

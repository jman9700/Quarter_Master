package io.github.jman9700.quartermaster.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jman9700.quartermaster.data.BackupService
import io.github.jman9700.quartermaster.data.InventoryRepository
import io.github.jman9700.quartermaster.data.OpenFoodFactsClient
import io.github.jman9700.quartermaster.db.QuarterMasterDatabase
import io.github.jman9700.quartermaster.ui.App
import io.github.jman9700.quartermaster.ui.AppDependencies
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import java.awt.Dimension
import java.awt.Frame
import java.io.File
import java.util.Properties

fun main() {
    // Set by Gradle (run and packaged app); a plain IDE run falls back to "dev".
    val version = System.getProperty("quartermaster.version") ?: "dev"
    val dataDir = AppDirs.data.apply { mkdirs() }

    // The schema overload creates the tables on first run and migrates by PRAGMA user_version after that.
    val driver = JdbcSqliteDriver(
        url = "jdbc:sqlite:${File(dataDir, "quartermaster.db").absolutePath}",
        properties = Properties(),
        schema = QuarterMasterDatabase.Schema,
    )
    var frame: Frame? = null
    val platform = DesktopPlatform(dataDir) { frame }
    val repository = InventoryRepository(driver, Dispatchers.IO)
    val deps = AppDependencies(
        repository = repository,
        backup = BackupService(repository, platform.imageStore, version),
        openFoodFacts = OpenFoodFactsClient(OpenFoodFactsClient.createHttpClient(HttpClient(OkHttp), version)),
        platform = platform,
        appVersion = version,
    )

    application {
        Window(
            onCloseRequest = {
                driver.close()
                exitApplication()
            },
            title = "Quarter Master",
            state = rememberWindowState(width = 500.dp, height = 680.dp),
        ) {
            LaunchedEffect(window) {
                frame = window
                window.minimumSize = Dimension(380, 500)
            }
            App(deps)
        }
    }
}

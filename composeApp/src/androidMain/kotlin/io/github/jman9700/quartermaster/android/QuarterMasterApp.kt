package io.github.jman9700.quartermaster.android

import android.app.Application
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.jman9700.quartermaster.BuildConfig
import io.github.jman9700.quartermaster.data.BackupService
import io.github.jman9700.quartermaster.data.InventoryRepository
import io.github.jman9700.quartermaster.data.OpenFoodFactsClient
import io.github.jman9700.quartermaster.db.QuarterMasterDatabase
import io.github.jman9700.quartermaster.ui.AppDependencies
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers

class QuarterMasterApp : Application() {

    lateinit var dependencies: AppDependencies
        private set

    override fun onCreate() {
        super.onCreate()
        val version = BuildConfig.VERSION_NAME
        val driver = AndroidSqliteDriver(QuarterMasterDatabase.Schema, this, "quartermaster.db")
        val platform = AndroidPlatform(this)
        val repository = InventoryRepository(driver, Dispatchers.IO)
        dependencies = AppDependencies(
            repository = repository,
            backup = BackupService(repository, platform.imageStore, version),
            openFoodFacts = OpenFoodFactsClient(OpenFoodFactsClient.createHttpClient(HttpClient(OkHttp), version)),
            platform = platform,
            appVersion = version,
        )
    }
}

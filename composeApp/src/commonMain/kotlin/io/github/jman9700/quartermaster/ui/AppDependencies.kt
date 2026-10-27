package io.github.jman9700.quartermaster.ui

import io.github.jman9700.quartermaster.data.BackupService
import io.github.jman9700.quartermaster.data.Environment
import io.github.jman9700.quartermaster.data.InventoryRepository
import io.github.jman9700.quartermaster.data.OpenFoodFactsClient
import io.github.jman9700.quartermaster.platform.PlatformServices

/** Everything the shared UI needs, built once per platform at startup. */
class AppDependencies(
    val repository: InventoryRepository,
    val backup: BackupService,
    val openFoodFacts: OpenFoodFactsClient,
    val platform: PlatformServices,
    val appVersion: String,
    val env: Environment = Environment.System,
)

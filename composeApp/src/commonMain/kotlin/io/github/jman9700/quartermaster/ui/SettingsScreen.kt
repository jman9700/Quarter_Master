package io.github.jman9700.quartermaster.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jman9700.quartermaster.data.BackupFormatException
import io.github.jman9700.quartermaster.platform.BackupFileResult
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

@Composable
fun SettingsScreen(
    deps: AppDependencies,
    onlineLookup: Boolean,
    today: LocalDate,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val files = deps.platform.rememberBackupFiles(
        onImportRead = { text ->
            scope.launch {
                busy = true
                try {
                    val s = deps.backup.import(text)
                    onMessage(
                        if (s.isEmpty) "Backup imported. Everything was already up to date."
                        else "Imported ${s.productsAdded + s.productsUpdated} products and ${s.itemsAdded + s.itemsUpdated} items",
                    )
                } catch (e: BackupFormatException) {
                    onMessage(e.message ?: "Couldn't import that file")
                } catch (e: Exception) {
                    onMessage("Import failed: ${e.message}")
                } finally {
                    busy = false
                }
            }
        },
        onResult = { result ->
            busy = false
            when (result) {
                BackupFileResult.Exported -> onMessage("Backup saved")
                is BackupFileResult.Failed -> onMessage(result.message)
                BackupFileResult.Cancelled -> Unit
            }
        },
    )

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Look up new products online", style = MaterialTheme.typography.titleSmall)
                Text(
                    "When you scan something new, prefill its name, size and photo from OpenFoodFacts. " +
                        "Only the barcode is sent. Everything else stays on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = onlineLookup, onCheckedChange = { scope.launch { deps.repository.setOnlineLookupEnabled(it) } })
        }

        HorizontalDivider()
        Text("Backup", style = MaterialTheme.typography.titleSmall)
        Text(
            "Your inventory lives only on this device. Export a backup file to keep it safe or move it to another device. " +
                "Importing merges a backup in: the newest version of each entry wins, and nothing is deleted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { deps.backup.export() }
                            .onSuccess { files.export("quarter-master-backup-$today.json", it) }
                            .onFailure { busy = false; onMessage("Export failed: ${it.message}") }
                    }
                },
            ) { Text("Export backup") }
            OutlinedButton(enabled = !busy, onClick = { files.import() }) { Text("Import backup") }
        }

        HorizontalDivider()
        Text("About", style = MaterialTheme.typography.titleSmall)
        Text("Quarter Master ${deps.appVersion}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Product data from OpenFoodFacts (openfoodfacts.org), available under the Open Database License.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

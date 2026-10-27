package io.github.jman9700.quartermaster.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jman9700.quartermaster.domain.Barcode
import io.github.jman9700.quartermaster.domain.BarcodeResult
import io.github.jman9700.quartermaster.domain.DuplicateBarcodeException
import io.github.jman9700.quartermaster.domain.LifespanStats
import io.github.jman9700.quartermaster.domain.ProductDraft
import io.github.jman9700.quartermaster.domain.ProductType
import io.github.jman9700.quartermaster.domain.UNIT_OPTIONS
import io.github.jman9700.quartermaster.domain.formatQuantity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

private enum class LookupState { Idle, Loading, Found, NotFound }

/** Create or edit a product type. Formerly the alpha's "Add Item Type" dialog. */
@Composable
fun ProductForm(
    deps: AppDependencies,
    existing: ProductType?,
    initialGtin: String?,
    onlineLookup: Boolean,
    today: LocalDate,
    onScanBarcode: (onCode: (String, io.github.jman9700.quartermaster.domain.BarcodeHint) -> Unit) -> Unit,
    onClose: () -> Unit,
    onSaved: (ProductType) -> Unit,
    onDeleted: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val repo = deps.repository
    val imageStore = deps.platform.imageStore
    val scope = rememberCoroutineScope()

    var barcode by remember { mutableStateOf(initialGtin?.let(Barcode::display) ?: "") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var lifespan by remember { mutableStateOf(existing?.lifespanDays?.toString() ?: "") }
    var quantity by remember { mutableStateOf(existing?.quantity?.let(::formatQuantity) ?: "1") }
    var unit by remember { mutableStateOf(existing?.unit ?: UNIT_OPTIONS.first()) }
    var imageFile by remember { mutableStateOf(existing?.imageFile) }
    var lookup by remember { mutableStateOf(LookupState.Idle) }
    var errors by remember { mutableStateOf(emptyMap<String, String>()) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val stats by remember(existing?.id) {
        existing?.let { repo.itemsForType(it.id) } ?: flowOf(emptyList())
    }.collectAsState(emptyList())
    val lifespanStats = remember(stats) { LifespanStats.from(stats) }

    fun lookUp(gtin14: String) {
        if (lookup == LookupState.Loading) return
        lookup = LookupState.Loading
        scope.launch {
            val suggestion = deps.openFoodFacts.lookup(gtin14)
            if (suggestion == null) {
                lookup = LookupState.NotFound
                return@launch
            }
            // Only fill blanks: never overwrite what the user already typed.
            if (name.isBlank()) suggestion.name?.let { name = it }
            if (suggestion.quantity != null && suggestion.unit != null && unit == UNIT_OPTIONS.first() && quantity == "1") {
                quantity = formatQuantity(suggestion.quantity)
                unit = suggestion.unit
            }
            lookup = LookupState.Found
            if (imageFile == null && suggestion.imageUrl != null) {
                deps.openFoodFacts.downloadImage(suggestion.imageUrl)?.let { bytes ->
                    runCatching { imageStore.save(bytes) }.getOrNull()?.let { imageFile = it }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (existing == null && initialGtin != null && onlineLookup) lookUp(initialGtin)
    }

    val picker = deps.platform.rememberImagePicker { bytes ->
        scope.launch {
            runCatching { imageStore.save(bytes) }
                .onSuccess { imageFile = it }
                .onFailure { onMessage("Couldn't use that image") }
        }
    }

    fun save() {
        val problems = mutableMapOf<String, String>()
        val gtin = barcode.trim().takeIf { it.isNotEmpty() }?.let {
            when (val r = Barcode.normalize(it)) {
                is BarcodeResult.Valid -> r.gtin14
                is BarcodeResult.Invalid -> { problems["barcode"] = r.reason; null }
            }
        }
        if (name.isBlank()) problems["name"] = "Name is required"
        val days = lifespan.trim().toIntOrNull()
        if (days == null || days < 0) problems["lifespan"] = "Lifespan must be a whole number of days"
        val qty = quantity.trim().replace(',', '.').toDoubleOrNull()
        if (qty == null || qty <= 0) problems["quantity"] = "Quantity must be a number above 0"
        errors = problems
        if (problems.isNotEmpty() || days == null || qty == null) return

        val draft = ProductDraft(gtin, name.trim(), days, qty, unit, imageFile)
        saving = true
        scope.launch {
            try {
                val saved = if (existing == null) {
                    repo.createType(draft)
                } else {
                    repo.updateType(existing.id, draft)
                    existing.copy(gtin = gtin, name = draft.name, lifespanDays = days, quantity = qty, unit = unit, imageFile = imageFile)
                }
                onSaved(saved)
            } catch (e: DuplicateBarcodeException) {
                errors = mapOf("barcode" to "Already used by \"${e.existing.name}\"")
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (existing == null) "New product" else "Edit product") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete product") }
            },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = barcode,
                onValueChange = { barcode = it.filter(Char::isDigit).take(14); errors = errors - "barcode" },
                label = { Text("Barcode (optional)") },
                isError = "barcode" in errors,
                supportingText = {
                    Text(
                        errors["barcode"] ?: when (lookup) {
                            LookupState.Loading -> "Looking it up on OpenFoodFacts…"
                            LookupState.Found -> "Filled in from OpenFoodFacts. Check it's right."
                            LookupState.NotFound -> "Not found online. Fill it in by hand."
                            LookupState.Idle -> "Leave empty for things without one, like fresh fruit"
                        },
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    Row {
                        if (lookup == LookupState.Loading) {
                            CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                        } else if (onlineLookup && barcode.isNotEmpty()) {
                            IconButton(onClick = {
                                (Barcode.normalize(barcode) as? BarcodeResult.Valid)?.let { lookUp(it.gtin14) }
                                    ?: run { errors = errors + ("barcode" to "Enter a valid barcode first") }
                            }) { Icon(Icons.Default.CloudDownload, contentDescription = "Look up online") }
                        }
                        if (deps.platform.hasBarcodeScanner) {
                            IconButton(onClick = {
                                onScanBarcode { raw, hint ->
                                    when (val r = Barcode.normalize(raw, hint)) {
                                        is BarcodeResult.Valid -> { barcode = Barcode.display(r.gtin14); errors = errors - "barcode" }
                                        is BarcodeResult.Invalid -> errors = errors + ("barcode" to r.reason)
                                    }
                                }
                            }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan barcode") }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; errors = errors - "name" },
                label = { Text("Name") },
                isError = "name" in errors,
                supportingText = errors["name"]?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = lifespan,
                onValueChange = { lifespan = it.filter(Char::isDigit).take(5); errors = errors - "lifespan" },
                label = { Text("Lifespan (days)") },
                isError = "lifespan" in errors,
                supportingText = { Text(errors["lifespan"] ?: "How long it usually lasts after you bring it home") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (existing != null && lifespanStats.sampleCount > 0) {
                ObservedLifespanCard(lifespanStats, currentDays = lifespan.toIntOrNull()) { lifespan = it.toString() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it; errors = errors - "quantity" },
                    label = { Text("Quantity") },
                    isError = "quantity" in errors,
                    supportingText = errors["quantity"]?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                UnitDropdown(unit, { unit = it }, Modifier.weight(1f))
            }
            Text("Image", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProductImage(imageStore, imageFile, 96.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (picker.canTakePhoto) OutlinedButton(onClick = picker::takePhoto) { Text("Take photo") }
                    OutlinedButton(onClick = picker::pickFromGallery) { Text("Choose image") }
                    if (imageFile != null) TextButton(onClick = { imageFile = null }) { Text("Remove image") }
                }
            }
        }
        Button(
            onClick = ::save,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
        ) { Text(if (existing == null) "Save product" else "Save changes") }
    }

    if (confirmDelete && existing != null) {
        val count = stats.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = {
                Text(if (count == 0) "This product has no items." else "This also deletes its $count ${if (count == 1) "item" else "items"}, history included.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repo.deleteType(existing.id)
                        onDeleted(existing.name)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ObservedLifespanCard(stats: LifespanStats, currentDays: Int?, onApply: (Int) -> Unit) {
    val avg = stats.averageDays ?: return
    val suggested = stats.suggestedLifespanDays ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Observed lifespan", style = MaterialTheme.typography.labelLarge)
            val rounded = formatQuantity((avg * 10).roundToInt() / 10.0)
            Text(
                "Lasts about $rounded ${if (rounded == "1") "day" else "days"} on average, from ${stats.sampleCount} ${if (stats.sampleCount == 1) "item" else "items"} " +
                    "with a best-by date or that went bad.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (stats.spoiledCount > 0) {
                Text(
                    "Went bad early ${stats.spoiledEarlyCount} of ${stats.spoiledCount} times it spoiled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (currentDays != suggested && suggested >= 1) {
                TextButton(onClick = { onApply(suggested) }) { Text("Use $suggested ${if (suggested == 1) "day" else "days"} as the lifespan") }
            }
        }
    }
}

@Composable
private fun UnitDropdown(unit: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = unit,
            onValueChange = {},
            readOnly = true,
            label = { Text("Unit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            UNIT_OPTIONS.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onChange(option); expanded = false })
            }
        }
    }
}

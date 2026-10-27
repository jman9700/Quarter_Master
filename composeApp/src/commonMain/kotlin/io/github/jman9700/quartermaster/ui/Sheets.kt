package io.github.jman9700.quartermaster.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jman9700.quartermaster.data.ImageStore
import io.github.jman9700.quartermaster.domain.Barcode
import io.github.jman9700.quartermaster.domain.BarcodeResult
import io.github.jman9700.quartermaster.domain.Freshness
import io.github.jman9700.quartermaster.domain.InventoryItem
import io.github.jman9700.quartermaster.domain.ProductType
import io.github.jman9700.quartermaster.domain.RemovalReason
import kotlinx.datetime.LocalDate

@Composable
private fun SheetHeader(type: ProductType, imageStore: ImageStore, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProductImage(imageStore, type.imageFile, 56.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(type.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Choose a product to add without scanning: products without barcodes (fruit), or when the label won't scan. */
@Composable
fun ProductPickerSheet(
    types: List<ProductType>,
    imageStore: ImageStore,
    onPick: (ProductType) -> Unit,
    onTypeBarcode: () -> Unit,
    onNewProduct: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Add to inventory", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onTypeBarcode) { Text("Type a barcode") }
                OutlinedButton(onClick = onNewProduct) { Text("New product") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search products") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val shown = types.filter { it.matches(query) }
            if (shown.isEmpty()) {
                Text(
                    if (types.isEmpty()) "No products yet. Create one with \"New product\"." else "No matches",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(shown, key = { it.id }) { type ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(type) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProductImage(imageStore, type.imageFile, 40.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(type.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                type.amountText + (type.gtin?.let { " · ${Barcode.display(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddItemsSheet(
    type: ProductType,
    today: LocalDate,
    imageStore: ImageStore,
    onAdd: (count: Int, addedOn: LocalDate, bestBy: LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    var count by remember { mutableIntStateOf(1) }
    var addedOn by remember { mutableStateOf(today) }
    var bestBy by remember { mutableStateOf<LocalDate?>(null) }
    val expires = Freshness.expiresOn(addedOn, bestBy, type.lifespanDays)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetHeader(type, imageStore, type.amountText)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("How many", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { if (count > 1) count-- }, enabled = count > 1) { Icon(Icons.Default.Remove, contentDescription = "Fewer") }
                Text("$count", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { if (count < 99) count++ }) { Icon(Icons.Default.Add, contentDescription = "More") }
            }
            DateRow("Added", addedOn, placeholder = "", onChange = { addedOn = it })
            DateRow(
                "Best by",
                bestBy,
                placeholder = "Not set: uses the ${type.lifespanDays}-day lifespan",
                onChange = { bestBy = it },
                onClear = { bestBy = null },
                supporting = "Expires ${expires.pretty()} (${Freshness.describe(expires, today).lowercase()})",
            )
            Button(onClick = { onAdd(count, addedOn, bestBy) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (count == 1) "Add to inventory" else "Add $count to inventory")
            }
        }
    }
}

@Composable
fun ItemDetailSheet(
    item: InventoryItem,
    today: LocalDate,
    imageStore: ImageStore,
    onDatesChanged: (addedOn: LocalDate, bestBy: LocalDate?) -> Unit,
    onRemove: (RemovalReason) -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val inInventory = item.removedOn == null
    val expires = item.expiresOn

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetHeader(item.type, imageStore, item.type.amountText)
            if (inInventory) {
                HealthBar(Freshness.fraction(item.addedOn, expires, today), Modifier.padding(top = 4.dp))
                Text("Expires ${expires.pretty()}: ${Freshness.describe(expires, today).lowercase()}", style = MaterialTheme.typography.bodyMedium)
            } else {
                val removed = "${item.removalReason?.label ?: "Removed"} on ${item.removedOn?.pretty()}"
                Text(removed, style = MaterialTheme.typography.bodyLarge)
                item.spoiledDaysEarly?.let {
                    Text(
                        "Went bad $it ${if (it == 1) "day" else "days"} before its expected date (${expires.pretty()})",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DateRow("Added", item.addedOn, placeholder = "", onChange = { onDatesChanged(it, item.bestBy) })
            DateRow(
                "Best by",
                item.bestBy,
                placeholder = "Not set: uses the ${item.type.lifespanDays}-day lifespan",
                onChange = { onDatesChanged(item.addedOn, it) },
                onClear = { onDatesChanged(item.addedOn, null) },
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (inInventory) {
                Text("Take it out of the inventory", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onRemove(RemovalReason.USED) }, modifier = Modifier.weight(1f)) { Text("Used it") }
                    FilledTonalButton(onClick = { onRemove(RemovalReason.SPOILED) }, modifier = Modifier.weight(1f)) { Text("Went bad") }
                    OutlinedButton(onClick = { onRemove(RemovalReason.DISCARDED) }, modifier = Modifier.weight(1f)) { Text("Discard") }
                }
            } else {
                Button(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("Put back in inventory") }
            }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Delete: logged by mistake", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this item?") },
            text = { Text("Use this for items added by mistake. It won't appear in history or count toward lifespan stats.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Typed entry for when the camera can't read a label (or there's no camera). */
@Composable
fun BarcodeEntryDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val error = code.takeIf { it.length >= 8 }?.let { (Barcode.normalize(it) as? BarcodeResult.Invalid)?.reason }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter barcode") },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { new -> code = new.filter(Char::isDigit).take(14) },
                label = { Text("UPC / EAN digits") },
                isError = error != null,
                supportingText = { Text(error ?: "The numbers under the barcode") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (code.isNotEmpty()) onSubmit(code) }),
            )
        },
        confirmButton = { TextButton(onClick = { onSubmit(code) }, enabled = code.length >= 8) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

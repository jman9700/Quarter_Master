package io.github.jman9700.quartermaster.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jman9700.quartermaster.data.ImageStore
import io.github.jman9700.quartermaster.domain.Barcode
import io.github.jman9700.quartermaster.domain.Freshness
import io.github.jman9700.quartermaster.domain.InventoryItem
import io.github.jman9700.quartermaster.domain.ProductType
import kotlinx.datetime.LocalDate

private val ListPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp)

@Composable
private fun ScreenTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp))
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

fun ProductType.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return name.contains(q, ignoreCase = true) || (gtin != null && q.all(Char::isDigit) && Barcode.display(gtin).contains(q))
}

// ---- Inventory --------------------------------------------------------------------------------

@Composable
fun InventoryScreen(
    items: List<InventoryItem>,
    today: LocalDate,
    imageStore: ImageStore,
    canScan: Boolean,
    onScan: () -> Unit,
    onAddManually: () -> Unit,
    onItemClick: (InventoryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Inventory")
            if (items.isNotEmpty()) SearchField(query, { query = it }, "Search inventory")
            val shown = items.filter { it.type.matches(query) }
            if (items.isEmpty()) {
                EmptyState("No items yet", "Scan a barcode to add your first item, or tap + to pick a product without one.")
            } else {
                LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { item -> InventoryCard(item, today, imageStore) { onItemClick(item) } }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallFloatingActionButton(onClick = onAddManually) { Icon(Icons.Default.Add, contentDescription = "Add without scanning") }
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(if (canScan) Icons.Default.QrCodeScanner else Icons.Default.Keyboard, contentDescription = null) },
                text = { Text(if (canScan) "Scan" else "Enter barcode") },
            )
        }
    }
}

@Composable
private fun InventoryCard(item: InventoryItem, today: LocalDate, imageStore: ImageStore, onClick: () -> Unit) {
    val expires = item.expiresOn
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductImage(imageStore, item.type.imageFile, 48.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(item.type.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.type.amountText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Added ${item.addedOn.pretty()}" + (item.bestBy?.let { " · Best by ${it.pretty()}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(Freshness.describe(expires, today), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HealthBar(Freshness.fraction(item.addedOn, expires, today), Modifier.padding(top = 10.dp))
        }
    }
}

// ---- Products (the alpha's "UPC Lookup") ------------------------------------------------------

@Composable
fun ProductsScreen(
    types: List<ProductType>,
    activeItems: List<InventoryItem>,
    imageStore: ImageStore,
    onProductClick: (ProductType) -> Unit,
    onNewProduct: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val inStock = activeItems.groupingBy { it.type.id }.eachCount()
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Products")
            if (types.isNotEmpty()) SearchField(query, { query = it }, "Search by name or barcode")
            if (types.isEmpty()) {
                EmptyState("No products yet", "Products are created the first time you scan something new, or with the button below.")
            } else {
                LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(types.filter { it.matches(query) }, key = { it.id }) { type ->
                        ProductCard(type, inStock[type.id] ?: 0, imageStore) { onProductClick(type) }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNewProduct,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("New product") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
fun ProductCard(type: ProductType, inStock: Int?, imageStore: ImageStore, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(type.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    type.gtin?.let { "UPC: ${Barcode.display(it)}" } ?: "No barcode",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Amount: ${type.amountText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Lifespan: ${type.lifespanDays} days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (inStock != null && inStock > 0) {
                    Text("$inStock in inventory", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ProductImage(imageStore, type.imageFile, 72.dp, Modifier.padding(start = 8.dp))
        }
    }
}

// ---- History ----------------------------------------------------------------------------------

@Composable
fun HistoryScreen(
    items: List<InventoryItem>,
    imageStore: ImageStore,
    onItemClick: (InventoryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ScreenTitle("History")
        if (items.isEmpty()) {
            EmptyState("Nothing here yet", "Items you mark as used, gone bad or discarded show up here. You can put them back if that was a mistake.")
            return
        }
        LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { onItemClick(item) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProductImage(imageStore, item.type.imageFile, 40.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(item.type.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${item.removalReason?.label ?: "Removed"} ${item.removedOn?.pretty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.spoiledDaysEarly?.let {
                            Text(
                                "Spoiled $it ${if (it == 1) "day" else "days"} early",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

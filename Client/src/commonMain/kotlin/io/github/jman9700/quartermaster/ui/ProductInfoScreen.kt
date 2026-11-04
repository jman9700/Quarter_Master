package io.github.jman9700.quartermaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jman9700.quartermaster.data.OpenFoodFactsClient
import io.github.jman9700.quartermaster.domain.Barcode
import io.github.jman9700.quartermaster.domain.Freshness
import io.github.jman9700.quartermaster.domain.InventoryItem
import io.github.jman9700.quartermaster.domain.LifespanStats
import io.github.jman9700.quartermaster.domain.LookupStatus
import io.github.jman9700.quartermaster.domain.Nutrients
import io.github.jman9700.quartermaster.domain.Nutrition
import io.github.jman9700.quartermaster.domain.ProductType
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/**
 * Everything about one product: details, what's in the inventory, how long it really lasts, and
 * its nutrition facts. Opened from the Products tab and from an inventory item.
 */
@Composable
fun ProductInfoScreen(
    deps: AppDependencies,
    type: ProductType,
    onlineLookup: Boolean,
    today: LocalDate,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onAddToInventory: () -> Unit,
    onItemClick: (InventoryItem) -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val items by remember(type.id) { deps.repository.itemsForType(type.id) }.collectAsState(emptyList())
    val inStock = items.filter { it.removedOn == null }.sortedBy { it.expiresOn }
    val stats = remember(items) { LifespanStats.from(items) }
    var checking by remember { mutableStateOf(false) }

    fun tryLookupNow() {
        checking = true
        scope.launch {
            val run = deps.lookup.processQueue()
            checking = false
            if (run == null || run.stillPending > 0) onMessage("Still no connection. It will try again next time you open the app.")
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Product info") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = { IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit product") } },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductImage(deps.platform.imageStore, type.imageFile, 96.dp)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(type.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(type.amountText, color = MaterialTheme.colorScheme.primary)
                    Text(
                        type.gtin?.let { "UPC ${Barcode.display(it)}" } ?: "No barcode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Lifespan: ${type.lifespanDays} days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (type.isLookupPending) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Waiting for internet", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (type.nutrition == null) {
                                    "Nutrition and product details are missing for now. They'll fill in from the food database " +
                                        "the next time you open the app with a connection."
                                } else {
                                    "The photo didn't make it through on a weak connection. It will be fetched the next time " +
                                        "you open the app with a connection."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = ::tryLookupNow) { Text("Try now") }
                        }
                    }
                }
            }

            Button(onClick = onAddToInventory, modifier = Modifier.fillMaxWidth()) { Text("Add to inventory") }

            SectionTitle(if (inStock.isEmpty()) "None in inventory" else "In inventory (${inStock.size})")
            inStock.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onItemClick(item) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Added ${item.addedOn.pretty()}", style = MaterialTheme.typography.bodyMedium)
                        Text(Freshness.describe(item.expiresOn, today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HealthBar(Freshness.fraction(item.addedOn, item.expiresOn, today), Modifier.width(120.dp))
                }
            }

            if (stats.sampleCount > 0) {
                ObservedLifespanCard(stats, currentDays = type.lifespanDays) { days ->
                    scope.launch {
                        deps.repository.setLifespan(type.id, days)
                        onMessage("Lifespan set to $days days")
                    }
                }
            }

            HorizontalDivider()
            SectionTitle("Nutrition")
            NutritionSection(type, onlineLookup)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NutritionSection(type: ProductType, onlineLookup: Boolean) {
    val uriHandler = LocalUriHandler.current
    val nutrition = type.nutrition
    val gtin = type.gtin

    when {
        gtin == null -> Note("This product has no barcode, so there's no nutrition info to look up.")
        nutrition != null -> NutritionFacts(nutrition)
        type.lookupStatus == LookupStatus.PENDING -> Note("Pending: nutrition facts fill in when you're back online.")
        type.lookupStatus == LookupStatus.NOT_FOUND -> Note("OpenFoodFacts doesn't have this product yet. Anyone can add it on their site.")
        type.lookupStatus == LookupStatus.FOUND -> Note("OpenFoodFacts has this product, but no nutrition facts for it yet.")
        !onlineLookup -> Note("Online lookup is turned off in Settings.")
        else -> Note("Not looked up yet. It will be the next time you open the app with a connection.")
    }

    if (gtin != null) {
        TextButton(onClick = { uriHandler.openUri(OpenFoodFactsClient.productPageUrl(gtin)) }) {
            Text(if (type.lookupStatus == LookupStatus.NOT_FOUND) "Open on OpenFoodFacts" else "More on OpenFoodFacts")
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        }
        Text(
            "Nutrition data from OpenFoodFacts (openfoodfacts.org), under the Open Database License. It may be incomplete or out of date; check the package.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NutritionFacts(n: Nutrition) {
    var showIngredients by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (n.nutriScore != null || n.novaGroup != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                n.nutriScore?.let { NutriScoreBadge(it) }
                n.novaGroup?.let { NovaBadge(it) }
            }
        }
        n.servingSize?.let { Text("Serving size: $it", style = MaterialTheme.typography.bodyMedium) }
        if (n.perServing != null || n.per100g != null) NutrientTable(n.perServing, n.per100g)
        if (n.allergens.isNotEmpty()) {
            Column {
                Text("Allergens", style = MaterialTheme.typography.labelLarge)
                Text(n.allergens.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            }
        }
        n.ingredients?.let { text ->
            Column {
                TextButton(onClick = { showIngredients = !showIngredients }) { Text(if (showIngredients) "Hide ingredients" else "Show ingredients") }
                if (showIngredients) Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private data class NutrientRow(val label: String, val unit: String, val pick: (Nutrients) -> Double?, val scale: Double = 1.0, val indent: Boolean = false)

private val NUTRIENT_ROWS = listOf(
    NutrientRow("Calories", "kcal", { it.energyKcal }),
    NutrientRow("Fat", "g", { it.fat }),
    NutrientRow("Saturated fat", "g", { it.saturatedFat }, indent = true),
    NutrientRow("Carbohydrates", "g", { it.carbohydrates }),
    NutrientRow("Sugars", "g", { it.sugars }, indent = true),
    NutrientRow("Fiber", "g", { it.fiber }, indent = true),
    NutrientRow("Protein", "g", { it.proteins }),
    NutrientRow("Sodium", "mg", { it.sodium }, scale = 1000.0),
)

@Composable
private fun NutrientTable(perServing: Nutrients?, per100g: Nutrients?) {
    val columns = listOfNotNull(perServing?.let { "Per serving" to it }, per100g?.let { "Per 100 g/ml" to it })
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Spacer(Modifier.weight(1.4f))
                columns.forEach { (title, _) ->
                    Text(title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
            }
            NUTRIENT_ROWS.forEach { row ->
                val values = columns.map { (_, n) -> row.pick(n)?.times(row.scale) }
                if (values.all { it == null }) return@forEach
                Row {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (row.indent) FontWeight.Normal else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1.4f).padding(start = if (row.indent) 12.dp else 0.dp),
                    )
                    values.forEach { v ->
                        Text(
                            v?.let { "${formatAmount(it)} ${row.unit}" } ?: "–",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Whole numbers from 10 up, one decimal below that. */
internal fun formatAmount(v: Double): String =
    if (v >= 10) v.roundToInt().toString()
    else ((v * 10).roundToInt() / 10.0).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }

private val NUTRI_SCORE_COLORS = mapOf(
    "a" to Color(0xFF038141), "b" to Color(0xFF85BB2F), "c" to Color(0xFFFECB02), "d" to Color(0xFFEE8100), "e" to Color(0xFFE63E11),
)

@Composable
private fun NutriScoreBadge(grade: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Nutri-Score", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(NUTRI_SCORE_COLORS[grade] ?: Color.Gray),
            contentAlignment = Alignment.Center,
        ) {
            Text(grade.uppercase(), color = if (grade == "c") Color.Black else Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

private val NOVA_LABELS = mapOf(
    1 to "Unprocessed or minimally processed",
    2 to "Processed culinary ingredient",
    3 to "Processed food",
    4 to "Ultra-processed food",
)

@Composable
private fun NovaBadge(group: Int) {
    Column {
        Text("NOVA $group", style = MaterialTheme.typography.labelLarge)
        Text(NOVA_LABELS[group].orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

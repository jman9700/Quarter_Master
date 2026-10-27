package io.github.jman9700.quartermaster.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import io.github.jman9700.quartermaster.domain.Barcode
import io.github.jman9700.quartermaster.domain.BarcodeHint
import io.github.jman9700.quartermaster.domain.BarcodeResult
import io.github.jman9700.quartermaster.domain.ProductType
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    Inventory("Inventory", Icons.Default.Inventory2),
    Products("Products", Icons.Default.Category),
    History("History", Icons.Default.History),
    Settings("Settings", Icons.Default.Settings),
}

/** Bottom sheets. Only one is open at a time. */
private sealed interface Sheet {
    data object PickProduct : Sheet
    data class AddItems(val type: ProductType) : Sheet
    data class ItemDetail(val itemId: String) : Sheet
}

/** The full-screen product editor. [gtin] prefills a new product; [addAfterSave] chains into adding items. */
private data class ProductEditor(val existing: ProductType?, val gtin: String?, val addAfterSave: Boolean)

private sealed interface ScanTarget {
    data object AddItem : ScanTarget
    data class FillBarcode(val onCode: (String, BarcodeHint) -> Unit) : ScanTarget
}

@Composable
fun App(deps: AppDependencies) = QuarterMasterTheme {
    val repo = deps.repository
    val platform = deps.platform
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val today by rememberToday(deps.env)

    val types by repo.productTypes.collectAsState(emptyList())
    val active by repo.activeItems.collectAsState(emptyList())
    val history by repo.history.collectAsState(emptyList())
    val onlineLookup by repo.onlineLookupEnabled.collectAsState(true)

    var tab by rememberSaveable { mutableStateOf(Tab.Inventory) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var editor by remember { mutableStateOf<ProductEditor?>(null) }
    var scanTarget by remember { mutableStateOf<ScanTarget?>(null) }
    var typingBarcode by remember { mutableStateOf(false) }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    /** A barcode was scanned or typed: add another of a known product, or register a new one. */
    fun handleCode(raw: String, hint: BarcodeHint) {
        when (val result = Barcode.normalize(raw, hint)) {
            is BarcodeResult.Invalid -> message(result.reason)
            is BarcodeResult.Valid -> scope.launch {
                val existing = repo.findByGtin(result.gtin14)
                sheet = null
                if (existing != null) {
                    sheet = Sheet.AddItems(existing)
                } else {
                    editor = ProductEditor(existing = null, gtin = result.gtin14, addAfterSave = true)
                }
            }
        }
    }

    fun startScan() {
        if (platform.hasBarcodeScanner) scanTarget = ScanTarget.AddItem else typingBarcode = true
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            val modifier = Modifier.padding(padding)
            when (tab) {
                Tab.Inventory -> InventoryScreen(
                    items = active,
                    today = today,
                    imageStore = platform.imageStore,
                    canScan = platform.hasBarcodeScanner,
                    onScan = ::startScan,
                    onAddManually = { sheet = Sheet.PickProduct },
                    onItemClick = { sheet = Sheet.ItemDetail(it.id) },
                    modifier = modifier,
                )
                Tab.Products -> ProductsScreen(
                    types = types,
                    activeItems = active,
                    imageStore = platform.imageStore,
                    onProductClick = { editor = ProductEditor(it, it.gtin, addAfterSave = false) },
                    onNewProduct = { editor = ProductEditor(null, null, addAfterSave = false) },
                    modifier = modifier,
                )
                Tab.History -> HistoryScreen(
                    items = history,
                    imageStore = platform.imageStore,
                    onItemClick = { sheet = Sheet.ItemDetail(it.id) },
                    modifier = modifier,
                )
                Tab.Settings -> SettingsScreen(deps = deps, onlineLookup = onlineLookup, today = today, onMessage = ::message, modifier = modifier)
            }
        }

        editor?.let { e ->
            platform.BackHandler(enabled = scanTarget == null) { editor = null }
            Surface(Modifier.fillMaxSize()) {
                ProductForm(
                    deps = deps,
                    existing = e.existing,
                    initialGtin = e.gtin,
                    onlineLookup = onlineLookup,
                    today = today,
                    onScanBarcode = { onCode -> scanTarget = ScanTarget.FillBarcode(onCode) },
                    onClose = { editor = null },
                    onSaved = { saved ->
                        editor = null
                        if (e.addAfterSave) sheet = Sheet.AddItems(saved) else message("Saved ${saved.name}")
                    },
                    onDeleted = { name ->
                        editor = null
                        message("Deleted $name")
                    },
                    onMessage = ::message,
                )
            }
        }

        scanTarget?.let { target ->
            platform.BackHandler(enabled = true) { scanTarget = null }
            platform.BarcodeScanner(
                onScanned = { raw, hint ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scanTarget = null
                    when (target) {
                        ScanTarget.AddItem -> handleCode(raw, hint)
                        is ScanTarget.FillBarcode -> target.onCode(raw, hint)
                    }
                },
                onClose = { scanTarget = null },
            )
        }
    }

    when (val s = sheet) {
        null -> Unit
        Sheet.PickProduct -> ProductPickerSheet(
            types = types,
            imageStore = platform.imageStore,
            onPick = { sheet = Sheet.AddItems(it) },
            onTypeBarcode = {
                sheet = null
                typingBarcode = true
            },
            onNewProduct = {
                sheet = null
                editor = ProductEditor(null, null, addAfterSave = true)
            },
            onDismiss = { sheet = null },
        )
        is Sheet.AddItems -> AddItemsSheet(
            type = s.type,
            today = today,
            imageStore = platform.imageStore,
            onAdd = { count, addedOn, bestBy ->
                scope.launch {
                    repo.addItems(s.type.id, count, addedOn, bestBy)
                    sheet = null
                    tab = Tab.Inventory
                    message(if (count == 1) "Added ${s.type.name}" else "Added $count × ${s.type.name}")
                }
            },
            onDismiss = { sheet = null },
        )
        is Sheet.ItemDetail -> {
            val item = active.firstOrNull { it.id == s.itemId } ?: history.firstOrNull { it.id == s.itemId }
            if (item == null) {
                sheet = null
            } else {
                ItemDetailSheet(
                    item = item,
                    today = today,
                    imageStore = platform.imageStore,
                    onDatesChanged = { addedOn, bestBy -> scope.launch { repo.updateItemDates(item.id, addedOn, bestBy) } },
                    onRemove = { reason ->
                        scope.launch {
                            repo.removeItem(item.id, reason, today)
                            sheet = null
                            message("${item.type.name}: ${reason.label.lowercase()}")
                        }
                    },
                    onRestore = {
                        scope.launch {
                            repo.restoreItem(item.id)
                            sheet = null
                            message("${item.type.name} is back in the inventory")
                        }
                    },
                    onDelete = {
                        scope.launch {
                            repo.deleteItem(item.id)
                            sheet = null
                            message("Deleted ${item.type.name}")
                        }
                    },
                    onDismiss = { sheet = null },
                )
            }
        }
    }

    if (typingBarcode) {
        BarcodeEntryDialog(
            onSubmit = { code ->
                typingBarcode = false
                handleCode(code, BarcodeHint.UNKNOWN)
            },
            onDismiss = { typingBarcode = false },
        )
    }
}

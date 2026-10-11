package staff.android.release

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable private fun Brand(action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_common_goods), null, Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Text("Common Goods", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onAction) { Text(action) }
    }
}
@Composable private fun Photo(product: Product, modifier: Modifier) {
    val context = LocalContext.current
    val images = remember(context.applicationContext) { ProductImages.get(context) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, product.imageKey) { value = images.load(product.imageKey) }
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), product.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
@Composable fun ShoppingApp(model: ReleaseModel, openOrder: Boolean, requestNotifications: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(if (openOrder) "order" else "shop") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val back = { page = if (page == "updates") "order" else "shop"; selected = null }
    BackHandler(page != "shop" || selected != null) { back() }
    CommonTheme {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 960.dp).fillMaxWidth().semantics { testTagsAsResourceId = true }) {
                    if (page == "shop") Shop(selected, { selected = it }, { page = "order" })
                    else OrderPage(state, page == "updates", back,
                        { page = "updates"; model.openUpdates() },
                        { enabled -> model.setDeliveryUpdates(enabled, requestNotifications) }, requestNotifications)
                }
            }
        }
    }
}
@Composable private fun Shop(selected: String?, select: (String?) -> Unit, openOrder: () -> Unit) {
    val product = Catalog.featured.firstOrNull { it.id == selected }
    if (product != null) {
        LazyColumn(Modifier.fillMaxSize().testTag("product_detail"), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Brand("Back") { select(null) } }
            item { Photo(product, Modifier.fillMaxWidth().padding(horizontal = 20.dp).aspectRatio(1.25f)) }
            item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(product.title, style = MaterialTheme.typography.displaySmall)
                Text(product.price, style = MaterialTheme.typography.headlineSmall)
                Text(product.description, style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = openOrder, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Your order") }
            } }
        }
        return
    }
    val config = LocalConfiguration.current
    val columns = if (config.fontScale >= 1.5f) 1 else if (config.screenWidthDp >= 700) 3 else 2
    LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().testTag("shop"),
        contentPadding = PaddingValues(bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Brand("Orders", openOrder) }
        item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Good things,\nevery day.", style = MaterialTheme.typography.displaySmall)
            Text("A considered collection for everyday living.", style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = openOrder, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("open_order")) { Text("Your order") }
        } }
        items(Catalog.featured, key = { it.id }) { product -> Column(Modifier.padding(horizontal = 12.dp)) {
            Photo(product, Modifier.fillMaxWidth().aspectRatio(1f))
            Text(product.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
            Text(product.price, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { select(product.id) }, contentPadding = PaddingValues(0.dp)) { Text("Explore ${product.title}") }
        } }
    }
}
@Composable private fun OrderPage(state: ShoppingState, updates: Boolean, back: () -> Unit, viewUpdates: () -> Unit, preference: (Boolean) -> Unit, requestNotifications: () -> Unit) {
    val camera = Catalog.featured.first { it.id == "camera" }
    LazyColumn(Modifier.fillMaxSize().testTag(if (updates) "updates_screen" else "order_screen"), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Brand(if (updates) "Order" else "Shop", back) }
        item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (updates) "Your delivery." else "Good things,\nremembered.", style = MaterialTheme.typography.displaySmall)
            Text("Your Camera, from our collection to your everyday.", style = MaterialTheme.typography.bodyLarge)
        } }
        if (updates) item { Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when {
                    state.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("updates_loading")); Text("Finding your latest update…") }
                    state.disabled -> { Text("Your order is safe", Modifier.testTag("updates_disabled"), style = MaterialTheme.typography.headlineSmall); Text("Detailed delivery updates are taking a short break. Your order details are still available below.") }
                    state.unavailable -> { Text("Updates couldn't be loaded", Modifier.testTag("updates_unavailable"), style = MaterialTheme.typography.headlineSmall); Text("Please try again when you're connected. Your saved order is still here.") }
                    state.message != null -> { Text(state.message.title, Modifier.testTag("delivery_status"), style = MaterialTheme.typography.headlineSmall); Text(state.message.detail, Modifier.testTag("delivery_detail"), style = MaterialTheme.typography.bodyLarge) }
                }
                if (!state.loading) OutlinedButton(onClick = viewUpdates, modifier = Modifier.fillMaxWidth().testTag("refresh_updates")) { Text("Refresh updates") }
            }
        } }
        item { Photo(camera, Modifier.fillMaxWidth().padding(horizontal = 20.dp).aspectRatio(if (LocalConfiguration.current.fontScale >= 1.5f) 1.5f else 2f)) }
        state.order?.let { order ->
            item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Camera", style = MaterialTheme.typography.headlineMedium)
                Text("Order ${order.id.uppercase()}", Modifier.testTag("order_id"), style = MaterialTheme.typography.bodyLarge)
                Text("Paid in full", style = MaterialTheme.typography.bodyLarge)
                Text(java.text.NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("en-IN")).apply { maximumFractionDigits = 0 }.format(order.totalMinor / 100.0), Modifier.testTag("order_total"), style = MaterialTheme.typography.headlineLarge)
                Text("Last saved delivery update: ${order.deliveryLabel}", Modifier.testTag("saved_delivery"))
                if (!updates) Button(onClick = viewUpdates, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("view_updates")) { Text("View delivery updates") }
                else OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("back_order")) { Text("Back to your order") }
            } }
            if (!updates) item { Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Delivery updates", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(checked = order.deliveryUpdates, onCheckedChange = preference, modifier = Modifier.testTag("delivery_updates_preference"))
                    }
                    Text("Choose notifications for this device. Your order is always available in the app.")
                    val access = state.notificationAccess
                    val label = when {
                        !order.deliveryUpdates -> "Delivery notifications are off."
                        access?.mayNotify == true -> "Delivery notifications are on."
                        access?.permissionGranted == false -> "Allow notifications to receive delivery updates."
                        access?.channelEnabled == false -> "Delivery notifications are off for this channel."
                        else -> "Notifications are off in system settings."
                    }
                    Text(label, Modifier.testTag("notification_status"))
                    if (order.deliveryUpdates && access?.permissionGranted == false) OutlinedButton(onClick = requestNotifications, modifier = Modifier.fillMaxWidth().testTag("allow_notifications")) { Text("Allow notifications") }
                }
            } }
        } ?: item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp)) }
    }
}

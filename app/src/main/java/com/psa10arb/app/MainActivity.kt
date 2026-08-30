package com.psa10arb.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.psa10arb.app.data.GradedRawCandidate
import com.psa10arb.app.data.LOOPBACK_REDIRECT_URI_HINT
import com.psa10arb.app.data.PcgCompareRow
import com.psa10arb.app.data.ScanRow

class MainActivity : ComponentActivity() {
    private val viewModel: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Psa10ArbApp(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Psa10ArbApp(viewModel: ScanViewModel) {
    val state by viewModel.uiState.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    if (state.showWatchlistLogin) {
        WatchlistLoginScreen(
            consentUrl = state.watchlistConsentUrl,
            waitingForRedirect = state.watchlistWaiting,
            onCancel = { viewModel.cancelWatchlistLogin() },
        )
        return
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Pokemon Card and Slab Arb") },
                    actions = {
                        Text("API calls today: ${state.apiCallsToday}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                        IconButton(onClick = { viewModel.openSettings() }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Ending-soon auctions") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("PCG vs PSA/CGC") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Raw grading (Japan)") })
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> AuctionScreen(state, viewModel)
                1 -> PcgScreen(state, viewModel)
                else -> RawScreen(state, viewModel)
            }
        }
    }

    if (state.showSettings) {
        SettingsDialog(state, viewModel)
    }

    state.conflictPrompt?.let { pending ->
        fun name(f: PendingFeature) = when (f) {
            PendingFeature.AUCTION -> "Ending-soon auctions"
            PendingFeature.PCG -> "PCG vs PSA/CGC"
            PendingFeature.RAW -> "Raw grading (Japan)"
        }
        val runningName = listOf(PendingFeature.AUCTION, PendingFeature.PCG, PendingFeature.RAW)
            .filter { it != pending }
            .joinToString(" / ") { name(it) }
        val wantedName = name(pending)
        AlertDialog(
            onDismissRequest = { viewModel.dismissConflictPrompt() },
            title = { Text("Another bot is running") },
            text = { Text("Only one bot runs at a time (they share the same eBay developer key, and running more than one at once risks eBay rate-limiting them). Stop $runningName and start $wantedName instead?") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmSwitchFeature() }) { Text("Stop it, start $wantedName") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissConflictPrompt() }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun AuctionScreen(state: AppUiState, viewModel: ScanViewModel) {
    // A single LazyColumn (header rows as items, then the result cards) so
    // the whole screen scrolls as one unit regardless of screen size —
    // stacking a fixed-height header above a LazyColumn breaks on short/
    // small-screen devices where the header alone can exceed the viewport.
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Worldwide PSA10/CGC10 graded-slab auctions (top-tier characters, any seller) ending within " +
                    "5 hours, priced against the cheapest current AU buy-it-now listing for the same card " +
                    "(extreme-outlier prices excluded before picking a reference). Rows are confirmed against " +
                    "eBay's own structured item data (not just the title) before showing. Under \$150 landed, " +
                    "≥${state.minProfitPct}% est. profit only.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FeatureControls(
                running = state.auction.isRunning,
                scanningNow = state.auction.isScanningNow,
                progressText = state.auction.progressText,
                errorText = state.auction.errorText,
                onStart = { viewModel.startAuction() },
                onStop = { viewModel.stopAuction() },
                onRunNow = { viewModel.runAuctionNow() },
            )
            NoResultsHint(state.auction, state.auctionRows.isEmpty(), "checking ending-soon auctions")
        }
        items(state.auctionRows) { row -> AuctionRowCard(row) }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
fun NoResultsHint(feature: FeatureState, rowsEmpty: Boolean, actionDescription: String) {
    if (!rowsEmpty || feature.errorText != null || feature.isScanningNow) return
    val text = when {
        !feature.isRunning -> "Not running. Tap Start to begin $actionDescription."
        feature.lastScanAt > 0L -> {
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(feature.lastScanAt))
            "No results this cycle. Last checked $time — still running, will check again automatically."
        }
        else -> "Running — first check in progress…"
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun PcgScreen(state: AppUiState, viewModel: ScanViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "The cheapest active PCG-10 Japanese-language listing per card (PCG is a smaller/cheaper " +
                    "grader — a PCG-10 often undersells an equivalent PSA-10), priced against the cheapest " +
                    "current AU PSA-10 buy-it-now listing (CGC-10 shown alongside as bonus context; extreme-" +
                    "outlier prices excluded before picking either reference). Under \$150 landed, " +
                    "≥${state.minProfitPct}% est. profit only.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FeatureControls(
                running = state.pcg.isRunning,
                scanningNow = state.pcg.isScanningNow,
                progressText = state.pcg.progressText,
                errorText = state.pcg.errorText,
                onStart = { viewModel.startPcg() },
                onStop = { viewModel.stopPcg() },
                onRunNow = { viewModel.runPcgNow() },
            )
            NoResultsHint(state.pcg, state.pcgRows.isEmpty(), "checking PCG-10 listings vs current PSA/CGC AU listings")
        }
        items(state.pcgRows) { row -> PcgRowCard(row) }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
fun RawScreen(state: AppUiState, viewModel: ScanViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Raw (ungraded) top-tier-character listings located in Japan, under \$20 AUD landed. Each " +
                    "photo is CV-graded on-device (same grading engine as the Python store_grader_japan tool). " +
                    "No profit comparison here — only a predicted straight 10 makes the final list, and those " +
                    "are auto-added to your eBay watchlist if connected in Settings. Multi-quantity listings " +
                    "are skipped (the photo may not be the actual card). Scans 50 new listings at a time, one " +
                    "batch every 15 minutes, up to 900 total — no listing is ever graded twice. Results " +
                    "accumulate across batches.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FeatureControls(
                running = state.raw.isRunning,
                scanningNow = state.raw.isScanningNow,
                progressText = state.raw.progressText,
                errorText = state.raw.errorText,
                cadenceText = "auto-checks a new batch of 50 every 15 minutes",
                onStart = { viewModel.startRaw() },
                onStop = { viewModel.stopRaw() },
                onRunNow = { viewModel.runRawNow() },
            )
            NoResultsHint(state.raw, state.rawRows.isEmpty(), "grading raw Japan-sourced listings")
        }
        items(state.rawRows) { row -> RawRowCard(row) }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
fun FeatureControls(
    running: Boolean,
    scanningNow: Boolean,
    progressText: String,
    errorText: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRunNow: () -> Unit,
    cadenceText: String = "auto-checks once daily",
) {
    // FlowRow-free wrap: two buttons + status stacked so narrow devices
    // never clip content horizontally.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (running) {
            Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Stop")
            }
        } else {
            Button(onClick = onStart) { Text("Start") }
        }
        OutlinedButton(onClick = onRunNow, enabled = !scanningNow) { Text("Run now") }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        if (running) "Running · $cadenceText (use Run now for sooner)" else "Stopped",
        style = MaterialTheme.typography.bodySmall,
    )
    if (scanningNow) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(progressText, style = MaterialTheme.typography.bodySmall)
        }
    }
    errorText?.let {
        Spacer(modifier = Modifier.height(6.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AuctionRowCard(row: ScanRow) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(row.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            Text(row.source, style = MaterialTheme.typography.bodySmall)
            if (row.endTimeMillis != null) {
                val minsLeft = ((row.endTimeMillis - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                Text("Ends in ${minsLeft / 60}h ${minsLeft % 60}m", style = MaterialTheme.typography.bodySmall)
            }
            if (row.certNumber != null) {
                Text(
                    "Grade verified via eBay item details · cert #${row.certNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "Bid ${row.currency} %.2f + ship %.2f = landed %.2f".format(row.price, row.shipping, row.landedPrice),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("AU current BIN (reference): ${row.currency} %.2f".format(row.auReferencePrice), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Est. profit: %+.2f (%+.0f%%) after %.0f%% fee".format(row.estProfit, row.estProfitPct, row.estFeePct),
                fontWeight = FontWeight.Bold,
                color = if (row.estProfit >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row {
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.listingUrl))) }) { Text("Open listing") }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.auReferenceUrl))) }) { Text("Open BIN comp") }
            }
        }
    }
}

@Composable
fun PcgRowCard(row: PcgCompareRow) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(row.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            Text(row.source, style = MaterialTheme.typography.bodySmall)
            Text(
                "Price ${row.currency} %.2f + ship %.2f = landed %.2f".format(row.price, row.shipping, row.landedPrice),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("PSA-10 current AU BIN (reference): ${row.currency} %.2f".format(row.psaAskingPrice), style = MaterialTheme.typography.bodyMedium)
            if (row.cgcAskingPrice != null) {
                Text("CGC-10 current AU BIN: ${row.currency} %.2f".format(row.cgcAskingPrice), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Est. profit: %+.2f (%+.0f%%) after %.0f%% fee".format(row.estProfit, row.estProfitPct, row.estFeePct),
                fontWeight = FontWeight.Bold,
                color = if (row.estProfit >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row {
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.url))) }) { Text("Open listing") }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.psaAskingUrl))) }) { Text("Open PSA comp") }
            }
        }
    }
}

@Composable
fun RawRowCard(row: GradedRawCandidate) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(row.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Price ${row.currency} %.2f + ship %.2f = landed %.2f".format(row.price, row.shipping, row.landedPrice),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Predicted grade: ~${row.probableGrade}${if (row.isHolo) "  ·  holo" else ""}",
                fontWeight = FontWeight.Bold,
                color = when {
                    row.probableGrade >= 10 -> MaterialTheme.colorScheme.primary
                    row.probableGrade >= 8 -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.error
                },
            )
            if (row.limitingFactor.isNotBlank()) {
                Text("Limiting factor: ${row.limitingFactor}", style = MaterialTheme.typography.bodySmall)
            }
            if (row.probableGrade >= 10) {
                Text("Auto-watched if eBay watchlist is connected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.url))) }) { Text("Open listing") }
        }
    }
}

@Composable
fun SettingsDialog(state: AppUiState, viewModel: ScanViewModel) {
    var showRemoveConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { viewModel.dismissSettings() },
        title = { Text("eBay developer keys & scan settings") },
        text = {
            // Scrollable so this fits on small screens with many fields.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "This app is shared — enter your own eBay developer client ID/secret " +
                        "(from developer.ebay.com). They're stored encrypted on this device only, " +
                        "never bundled with the app.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.clientId,
                    onValueChange = viewModel::updateClientId,
                    label = { Text("EBAY_CLIENT_ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.clientSecret,
                    onValueChange = viewModel::updateClientSecret,
                    label = { Text("EBAY_CLIENT_SECRET") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { showRemoveConfirm = true }, enabled = state.hasCredentials) {
                    Text("Remove stored keys")
                }
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Watchlist auto-seed (optional): when connected, all three bots automatically add " +
                        "their qualifying results to your real eBay watchlist. Needs an eBay \"RuName\" from " +
                        "developer.ebay.com → User Tokens, configured to redirect to " +
                        "$LOOPBACK_REDIRECT_URI_HINT",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.ebayRuName,
                    onValueChange = viewModel::updateRuName,
                    label = { Text("EBAY_RUNAME") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.hasWatchlistAuth) {
                        OutlinedButton(onClick = { viewModel.disconnectWatchlist() }) { Text("Disconnect watchlist") }
                    } else {
                        Button(onClick = { viewModel.openWatchlistLogin() }) { Text("Connect watchlist") }
                    }
                    Text(
                        if (state.hasWatchlistAuth) "Connected" else "Not connected",
                        color = if (state.hasWatchlistAuth) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                OutlinedTextField(
                    value = state.feePct,
                    onValueChange = viewModel::updateFeePct,
                    label = { Text("Estimated resale fee %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.minProfitPct,
                    onValueChange = viewModel::updateMinProfitPct,
                    label = { Text("Minimum profit % to show") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.topN,
                    onValueChange = viewModel::updateTopN,
                    label = { Text("Max auction results to show") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Ending-soon auctions and PCG vs PSA/CGC auto-check once a day while running; Raw " +
                        "grading works through its backlog in batches of 50 every 15 minutes instead. Use " +
                        "each tab's \"Run now\" for an immediate check.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.saveSettings() }) { Text("Save") }
        },
        dismissButton = {
            if (state.hasCredentials) {
                TextButton(onClick = { viewModel.dismissSettings() }) { Text("Cancel") }
            }
        },
    )

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove stored keys?") },
            text = { Text("This deletes the saved eBay client ID/secret and cached token from this device, disconnects the watchlist, and stops all running scans.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeCredentials()
                    showRemoveConfirm = false
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

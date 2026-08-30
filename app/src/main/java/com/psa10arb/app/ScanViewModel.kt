package com.psa10arb.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.psa10arb.app.data.ApiCounter
import com.psa10arb.app.data.AppLogger
import com.psa10arb.app.data.EbayAuth
import com.psa10arb.app.data.EbayClient
import com.psa10arb.app.data.EbayUserAuth
import com.psa10arb.app.data.GradeCompareRepository
import com.psa10arb.app.data.GradedRawCandidate
import com.psa10arb.app.data.PcgCompareRow
import com.psa10arb.app.data.QuotaError
import com.psa10arb.app.data.RawGradingSource
import com.psa10arb.app.data.ScanRepository
import com.psa10arb.app.data.ScanRow
import com.psa10arb.app.data.Settings
import com.psa10arb.app.data.WatchlistSeeder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val MAX_RAW_LANDED_AUD = 20.0
private const val RAW_SOURCE_COUNTRY = "JP"
private const val WATCH_MIN_GRADE = 10

// Every bot auto-re-scans at most once a day — "Run now" is the only way to
// trigger an out-of-cycle scan. Keeps API usage/rate-limit risk predictable
// on a shared developer key rather than configurable per-bot intervals.
private const val DAILY_INTERVAL_MILLIS = 24L * 60 * 60_000L

// Raw grading is the exception: it works through its candidate backlog in
// small batches (RawGradingSource.BATCH_SIZE) rather than one big cycle, so
// it re-checks every 15 minutes instead of once a day.
private const val RAW_BATCH_INTERVAL_MILLIS = 15L * 60_000L

data class FeatureState(
    val isRunning: Boolean = false,
    val isScanningNow: Boolean = false,
    val progressText: String = "",
    val errorText: String? = null,
    val lastScanAt: Long = 0L,
)

enum class PendingFeature { AUCTION, PCG, RAW }

data class AppUiState(
    val clientId: String = "",
    val clientSecret: String = "",
    val feePct: String = "13",
    val minProfitPct: String = "30",
    val topN: String = "30",
    val ebayRuName: String = "",
    val showSettings: Boolean = true,
    val showWatchlistLogin: Boolean = false,
    val watchlistConsentUrl: String = "",
    val watchlistWaiting: Boolean = false,
    val hasCredentials: Boolean = false,
    val hasWatchlistAuth: Boolean = false,
    val apiCallsToday: Int = 0,
    val auction: FeatureState = FeatureState(),
    val auctionRows: List<ScanRow> = emptyList(),
    val pcg: FeatureState = FeatureState(),
    val pcgRows: List<PcgCompareRow> = emptyList(),
    val raw: FeatureState = FeatureState(),
    val rawRows: List<GradedRawCandidate> = emptyList(),
    /** Set when the user tried to start a bot while another one is already
     * running — only one bot runs at a time (all of them hit the same eBay
     * developer key; running more than one at once multiplies rate-limiting
     * risk). This is enforced strictly: starting a second bot always shows
     * this prompt rather than silently queueing or refusing. */
    val conflictPrompt: PendingFeature? = null,
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)
    private val apiCounter = ApiCounter(application)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val imageHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val auth = EbayAuth(settings, http)
    private val client = EbayClient(auth, apiCounter, http)
    private val auctionRepo = ScanRepository(client)
    private val pcgRepo = GradeCompareRepository(client, http)
    private val rawRepo = RawGradingSource(client, imageHttp)
    private val userAuth = EbayUserAuth(settings, http)
    private val watchlistSeeder = WatchlistSeeder(settings, userAuth, http)

    private var auctionJob: Job? = null
    private var pcgJob: Job? = null
    private var rawJob: Job? = null
    private var watchlistJob: Job? = null

    private val _uiState = MutableStateFlow(
        AppUiState(
            clientId = settings.clientId,
            clientSecret = settings.clientSecret,
            feePct = settings.feePct.toPlainString(),
            minProfitPct = settings.minProfitPct.toPlainString(),
            topN = settings.topN.toString(),
            ebayRuName = settings.ebayRuName,
            showSettings = !settings.hasCredentials,
            hasCredentials = settings.hasCredentials,
            hasWatchlistAuth = settings.hasWatchlistAuth,
            apiCallsToday = apiCounter.getCallCount(),
        )
    )
    val uiState: StateFlow<AppUiState> = _uiState

    fun updateClientId(v: String) = _uiState.update { it.copy(clientId = v) }
    fun updateClientSecret(v: String) = _uiState.update { it.copy(clientSecret = v) }
    fun updateFeePct(v: String) = _uiState.update { it.copy(feePct = v) }
    fun updateMinProfitPct(v: String) = _uiState.update { it.copy(minProfitPct = v) }
    fun updateTopN(v: String) = _uiState.update { it.copy(topN = v) }
    fun updateRuName(v: String) = _uiState.update { it.copy(ebayRuName = v) }
    fun openSettings() = _uiState.update { it.copy(showSettings = true) }
    fun dismissSettings() {
        if (settings.hasCredentials) _uiState.update { it.copy(showSettings = false) }
    }

    // ── Watchlist consent (optional, separate OAuth flow — see EbayUserAuth) ──
    fun openWatchlistLogin() {
        settings.ebayRuName = _uiState.value.ebayRuName.trim()
        if (settings.ebayRuName.isBlank()) {
            _uiState.update { it.copy(auction = it.auction.copy(errorText = "Enter your eBay RuName first (developer portal → User Tokens)")) }
            return
        }
        val consentUrl = userAuth.buildConsentUrl()
        _uiState.update {
            it.copy(showWatchlistLogin = true, showSettings = false, watchlistConsentUrl = consentUrl, watchlistWaiting = true)
        }
        watchlistJob?.cancel()
        watchlistJob = viewModelScope.launch {
            val code = userAuth.awaitAuthorizationCode()
            if (code != null) {
                val ok = userAuth.exchangeCode(code)
                _uiState.update {
                    it.copy(
                        showWatchlistLogin = false,
                        showSettings = true,
                        watchlistWaiting = false,
                        hasWatchlistAuth = settings.hasWatchlistAuth,
                        auction = it.auction.copy(errorText = if (!ok) "Watchlist connection failed — see log" else null),
                    )
                }
            } else {
                _uiState.update { it.copy(watchlistWaiting = false) }
            }
        }
    }

    fun cancelWatchlistLogin() {
        watchlistJob?.cancel()
        watchlistJob = null
        _uiState.update { it.copy(showWatchlistLogin = false, showSettings = true, watchlistWaiting = false) }
    }

    fun disconnectWatchlist() {
        settings.clearWatchlistAuth()
        _uiState.update { it.copy(hasWatchlistAuth = false) }
    }

    fun saveSettings() {
        val s = _uiState.value
        settings.clientId = s.clientId.trim()
        settings.clientSecret = s.clientSecret.trim()
        settings.feePct = s.feePct.toDoubleOrNull() ?: 13.0
        settings.minProfitPct = s.minProfitPct.toDoubleOrNull() ?: 30.0
        settings.topN = s.topN.toIntOrNull() ?: 30
        settings.ebayRuName = s.ebayRuName.trim()
        _uiState.update { it.copy(showSettings = false, hasCredentials = settings.hasCredentials) }
    }

    /** Wipes stored eBay dev keys + cached token — for the "share this app" flow
     * where each person supplies (and can remove) their own keys. Also stops
     * every feature loop since they'd immediately fail without credentials. */
    fun removeCredentials() {
        stopAuction()
        stopPcg()
        stopRaw()
        settings.clearCredentials()
        _uiState.update {
            it.copy(
                clientId = "",
                clientSecret = "",
                hasCredentials = false,
                hasWatchlistAuth = false,
                showSettings = true,
                auctionRows = emptyList(),
                pcgRows = emptyList(),
                rawRows = emptyList(),
            )
        }
    }

    private fun otherBotsRunning(mine: PendingFeature): Boolean = when (mine) {
        PendingFeature.AUCTION -> pcgJob?.isActive == true || rawJob?.isActive == true
        PendingFeature.PCG -> auctionJob?.isActive == true || rawJob?.isActive == true
        PendingFeature.RAW -> auctionJob?.isActive == true || pcgJob?.isActive == true
    }

    private fun stopAllExcept(mine: PendingFeature) {
        if (mine != PendingFeature.AUCTION) stopAuction()
        if (mine != PendingFeature.PCG) stopPcg()
        if (mine != PendingFeature.RAW) stopRaw()
    }

    // ── Feature 1: ending-soon graded auctions ───────────────────────────
    fun startAuction() {
        if (!requireCredentials()) return
        if (auctionJob?.isActive == true) return
        if (otherBotsRunning(PendingFeature.AUCTION)) {
            _uiState.update { it.copy(conflictPrompt = PendingFeature.AUCTION) }
            return
        }
        AppLogger.log("ViewModel", "Auction feature: Start")
        _uiState.update { it.copy(auction = it.auction.copy(isRunning = true, errorText = null)) }
        auctionJob = viewModelScope.launch {
            while (isActive) {
                _uiState.update { it.copy(auction = it.auction.copy(isScanningNow = true, progressText = "Starting…")) }
                val s = _uiState.value
                val feePct = s.feePct.toDoubleOrNull() ?: 13.0
                val minProfitPct = s.minProfitPct.toDoubleOrNull() ?: 30.0
                val topN = s.topN.toIntOrNull() ?: 30
                try {
                    val rows = auctionRepo.runScan(feePct, minProfitPct) { p ->
                        _uiState.update { it.copy(auction = it.auction.copy(progressText = p)) }
                    }
                    watchlistSeeder.seed(rows.map { it.itemId }, "Auction")
                    val now = System.currentTimeMillis()
                    _uiState.update {
                        // History accumulates across cycles rather than being replaced —
                        // keep previously-found rows that haven't ended yet, then let
                        // this cycle's fresh results overwrite/add on top.
                        val stillLive = it.auctionRows.filter { r -> r.endTimeMillis == null || r.endTimeMillis > now }.associateBy { r -> r.itemId }
                        val merged = (stillLive + rows.associateBy { r -> r.itemId }).values
                            .sortedByDescending { r -> r.estProfitPct }
                            .take(topN)
                        it.copy(
                            auctionRows = merged,
                            auction = it.auction.copy(isScanningNow = false, progressText = "", lastScanAt = now),
                            apiCallsToday = apiCounter.getCallCount(),
                        )
                    }
                } catch (e: QuotaError) {
                    AppLogger.log("ViewModel", "Auction feature stopped: ${e.message}")
                    _uiState.update { it.copy(auction = it.auction.copy(isScanningNow = false, progressText = "", errorText = e.message, isRunning = false), apiCallsToday = apiCounter.getCallCount()) }
                    break
                } catch (e: Exception) {
                    AppLogger.log("ViewModel", "Auction scan error (will retry next cycle): ${e.message}")
                    _uiState.update { it.copy(auction = it.auction.copy(isScanningNow = false, progressText = "", errorText = e.message ?: e.toString()), apiCallsToday = apiCounter.getCallCount()) }
                }
                kotlinx.coroutines.delay(DAILY_INTERVAL_MILLIS)
            }
        }
    }

    fun stopAuction() {
        AppLogger.log("ViewModel", "Auction feature: Stop")
        auctionJob?.cancel()
        auctionJob = null
        _uiState.update { it.copy(auction = it.auction.copy(isRunning = false, isScanningNow = false, progressText = "")) }
    }

    // ── Feature 2: PCG vs PSA/CGC ─────────────────────────────────────────
    fun startPcg() {
        if (!requireCredentials()) return
        if (pcgJob?.isActive == true) return
        if (otherBotsRunning(PendingFeature.PCG)) {
            _uiState.update { it.copy(conflictPrompt = PendingFeature.PCG) }
            return
        }
        AppLogger.log("ViewModel", "PCG feature: Start")
        _uiState.update { it.copy(pcg = it.pcg.copy(isRunning = true, errorText = null)) }
        pcgJob = viewModelScope.launch {
            while (isActive) {
                _uiState.update { it.copy(pcg = it.pcg.copy(isScanningNow = true, progressText = "Starting…")) }
                val s = _uiState.value
                val feePct = s.feePct.toDoubleOrNull() ?: 13.0
                val minProfitPct = s.minProfitPct.toDoubleOrNull() ?: 30.0
                try {
                    val rows = pcgRepo.runScan(feePct, minProfitPct) { p ->
                        _uiState.update { it.copy(pcg = it.pcg.copy(progressText = p)) }
                    }
                    watchlistSeeder.seed(rows.map { it.itemId }, "PCG")
                    _uiState.update {
                        // Same accumulate-and-prune history pattern as the auction feature —
                        // these are all BIN-style listings (no expiry), so a previous row is
                        // only dropped once this cycle's fresh results replace it outright.
                        val stillActive = it.pcgRows.associateBy { r -> r.itemId }
                        val merged = (stillActive + rows.associateBy { r -> r.itemId }).values
                            .sortedByDescending { r -> r.estProfitPct }
                        it.copy(
                            pcgRows = merged.toList(),
                            pcg = it.pcg.copy(isScanningNow = false, progressText = "", lastScanAt = System.currentTimeMillis()),
                            apiCallsToday = apiCounter.getCallCount(),
                        )
                    }
                } catch (e: QuotaError) {
                    AppLogger.log("ViewModel", "PCG feature stopped: ${e.message}")
                    _uiState.update { it.copy(pcg = it.pcg.copy(isScanningNow = false, progressText = "", errorText = e.message, isRunning = false), apiCallsToday = apiCounter.getCallCount()) }
                    break
                } catch (e: Exception) {
                    AppLogger.log("ViewModel", "PCG scan error (will retry next cycle): ${e.message}")
                    _uiState.update { it.copy(pcg = it.pcg.copy(isScanningNow = false, progressText = "", errorText = e.message ?: e.toString()), apiCallsToday = apiCounter.getCallCount()) }
                }
                kotlinx.coroutines.delay(DAILY_INTERVAL_MILLIS)
            }
        }
    }

    fun stopPcg() {
        AppLogger.log("ViewModel", "PCG feature: Stop")
        pcgJob?.cancel()
        pcgJob = null
        _uiState.update { it.copy(pcg = it.pcg.copy(isRunning = false, isScanningNow = false, progressText = "")) }
    }

    // ── Feature 3: Raw grading (Japan) ────────────────────────────────────
    fun startRaw() {
        if (!requireCredentials()) return
        if (rawJob?.isActive == true) return
        if (otherBotsRunning(PendingFeature.RAW)) {
            _uiState.update { it.copy(conflictPrompt = PendingFeature.RAW) }
            return
        }
        AppLogger.log("ViewModel", "Raw grading feature: Start")
        _uiState.update { it.copy(raw = it.raw.copy(isRunning = true, errorText = null)) }
        rawJob = viewModelScope.launch {
            while (isActive) {
                _uiState.update { it.copy(raw = it.raw.copy(isScanningNow = true, progressText = "Starting…")) }
                try {
                    val rows = rawRepo.fetchGraded(RAW_SOURCE_COUNTRY, MAX_RAW_LANDED_AUD) { p ->
                        _uiState.update { it.copy(raw = it.raw.copy(progressText = p)) }
                    }
                    watchlistSeeder.seed(
                        rows.filter { it.probableGrade >= WATCH_MIN_GRADE }.map { it.itemId },
                        "Raw grading",
                    )
                    _uiState.update {
                        // Record all found: each batch's qualifying results add to the
                        // running list rather than replacing it (RawGradingSource never
                        // re-scans the same listing, so there's nothing to prune here).
                        val merged = (it.rawRows.associateBy { r -> r.itemId } + rows.associateBy { r -> r.itemId }).values
                            .sortedByDescending { r -> r.probableGrade }
                        it.copy(
                            rawRows = merged.toList(),
                            raw = it.raw.copy(isScanningNow = false, progressText = "", lastScanAt = System.currentTimeMillis()),
                            apiCallsToday = apiCounter.getCallCount(),
                        )
                    }
                } catch (e: QuotaError) {
                    AppLogger.log("ViewModel", "Raw grading feature stopped: ${e.message}")
                    _uiState.update { it.copy(raw = it.raw.copy(isScanningNow = false, progressText = "", errorText = e.message, isRunning = false), apiCallsToday = apiCounter.getCallCount()) }
                    break
                } catch (e: Exception) {
                    AppLogger.log("ViewModel", "Raw grading scan error (will retry next cycle): ${e.message}")
                    _uiState.update { it.copy(raw = it.raw.copy(isScanningNow = false, progressText = "", errorText = e.message ?: e.toString()), apiCallsToday = apiCounter.getCallCount()) }
                }
                // Batches of 50 (RawGradingSource.BATCH_SIZE), one batch every 15
                // minutes, up to a 900-listing session cap — deliberately much more
                // frequent than the other two bots' once-daily cadence, since each
                // cycle only grades a small slice of the backlog rather than
                // re-running the whole search.
                kotlinx.coroutines.delay(RAW_BATCH_INTERVAL_MILLIS)
            }
        }
    }

    fun stopRaw() {
        AppLogger.log("ViewModel", "Raw grading feature: Stop")
        rawJob?.cancel()
        rawJob = null
        _uiState.update { it.copy(raw = it.raw.copy(isRunning = false, isScanningNow = false, progressText = "")) }
    }

    /** User confirmed "stop the other bot(s) and start this one instead". */
    fun confirmSwitchFeature() {
        val pending = _uiState.value.conflictPrompt ?: return
        _uiState.update { it.copy(conflictPrompt = null) }
        stopAllExcept(pending)
        when (pending) {
            PendingFeature.AUCTION -> startAuction()
            PendingFeature.PCG -> startPcg()
            PendingFeature.RAW -> startRaw()
        }
    }

    fun dismissConflictPrompt() = _uiState.update { it.copy(conflictPrompt = null) }

    /** Interrupts the current wait-for-next-interval delay and scans immediately. */
    fun runAuctionNow() {
        if (!requireCredentials()) return
        AppLogger.log("ViewModel", "Auction feature: Run now")
        stopAuction()
        startAuction()
    }

    fun runPcgNow() {
        if (!requireCredentials()) return
        AppLogger.log("ViewModel", "PCG feature: Run now")
        stopPcg()
        startPcg()
    }

    fun runRawNow() {
        if (!requireCredentials()) return
        AppLogger.log("ViewModel", "Raw grading feature: Run now")
        stopRaw()
        startRaw()
    }

    private fun requireCredentials(): Boolean {
        if (!settings.hasCredentials) {
            _uiState.update { it.copy(showSettings = true, auction = it.auction.copy(errorText = "Enter your eBay developer client ID/secret first")) }
            return false
        }
        return true
    }

    override fun onCleared() {
        super.onCleared()
        auctionJob?.cancel()
        pcgJob?.cancel()
        rawJob?.cancel()
        watchlistJob?.cancel()
    }

    private fun Double.toPlainString(): String =
        if (this == this.toLong().toDouble()) this.toLong().toString() else this.toString()
}

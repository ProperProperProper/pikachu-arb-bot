package com.psa10arb.app.data

import okhttp3.OkHttpClient

/**
 * Shared "auto-seed the user's real eBay watchlist" hook used by all three
 * bots. Only acts when the user has actually completed the watchlist
 * consent flow (Settings.hasWatchlistAuth) — with no eBay API creds/auth
 * populated this is a silent no-op, same as store_grader_japan.py's
 * watch_items() skipping quietly when there's no user token.
 */
class WatchlistSeeder(
    private val settings: Settings,
    private val userAuth: EbayUserAuth,
    http: OkHttpClient,
) {
    private val client = WatchlistClient(settings.clientId, http)

    suspend fun seed(itemIds: List<String>, label: String) {
        if (itemIds.isEmpty()) return
        if (!settings.hasWatchlistAuth) return
        val token = userAuth.getValidUserToken()
        if (token == null) {
            AppLogger.log("Watchlist", "$label: watchlist auth present but token unavailable — skipped")
            return
        }
        val added = client.addToWatchList(itemIds, token)
        AppLogger.log("Watchlist", "$label: ${added.size}/${itemIds.size} added to watchlist")
    }
}

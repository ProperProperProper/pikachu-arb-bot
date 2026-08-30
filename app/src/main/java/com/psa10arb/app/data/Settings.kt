package com.psa10arb.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Client id/secret and OAuth token live here (encrypted at rest) since there's
 * no .env file on a phone — this is the mobile equivalent of psa10_arb's
 * .env + .ebay_token.json.
 */
class Settings(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "psa10arb_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value).apply()

    var clientSecret: String
        get() = prefs.getString(KEY_CLIENT_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLIENT_SECRET, value).apply()

    var feePct: Double
        get() = prefs.getFloat(KEY_FEE_PCT, 13.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_FEE_PCT, value.toFloat()).apply()

    /** Only rows at/above this estimated profit % are surfaced. Default 30%. */
    var minProfitPct: Double
        get() = prefs.getFloat(KEY_MIN_PROFIT_PCT, 30.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_MIN_PROFIT_PCT, value.toFloat()).apply()

    var topN: Int
        get() = prefs.getInt(KEY_TOP_N, 30)
        set(value) = prefs.edit().putInt(KEY_TOP_N, value).apply()

    // ── App OAuth token cache (client-credentials grant; mirrors .ebay_token.json) ──
    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var tokenExpiresAt: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_TOKEN_EXPIRES_AT, value).apply()

    val hasCredentials: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    // ── Watchlist (user OAuth, authorization-code grant; mirrors .ebay_user_token.json) ──
    // Separate from the client-credentials token above: this one authorizes acting on
    // the user's real eBay account (AddToWatchList), so it needs its own consent flow
    // and its own RuName (each shared user's own eBay dev account has its own RuName —
    // can't be baked into the app any more than the client id/secret can).
    var ebayRuName: String
        get() = prefs.getString(KEY_RUNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RUNAME, value).apply()

    var watchlistAccessToken: String?
        get() = prefs.getString(KEY_WATCHLIST_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_WATCHLIST_ACCESS_TOKEN, value).apply()

    var watchlistRefreshToken: String?
        get() = prefs.getString(KEY_WATCHLIST_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_WATCHLIST_REFRESH_TOKEN, value).apply()

    var watchlistAccessTokenExpiresAt: Long
        get() = prefs.getLong(KEY_WATCHLIST_EXPIRES_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_WATCHLIST_EXPIRES_AT, value).apply()

    /** True once the user has completed the watchlist consent flow — gates the
     * auto-seed-watchlist behavior in all three bots. */
    val hasWatchlistAuth: Boolean
        get() = !watchlistRefreshToken.isNullOrBlank()

    /** Disconnects watchlist auto-seeding without touching the eBay dev keys —
     * a separate, narrower action than removeCredentials/clearCredentials. */
    fun clearWatchlistAuth() {
        prefs.edit()
            .remove(KEY_WATCHLIST_ACCESS_TOKEN)
            .remove(KEY_WATCHLIST_REFRESH_TOKEN)
            .remove(KEY_WATCHLIST_EXPIRES_AT)
            .apply()
    }

    /** Wipes the stored client ID/secret and any cached token — used by the
     * Settings screen's "Remove keys" action. Each person who runs this app
     * (it's shared, not just for one user) supplies and can clear their own
     * eBay developer keys; none are baked into the app itself. */
    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_CLIENT_ID)
            .remove(KEY_CLIENT_SECRET)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_TOKEN_EXPIRES_AT)
            .apply()
        clearWatchlistAuth()
    }

    companion object {
        private const val KEY_CLIENT_ID = "ebay_client_id"
        private const val KEY_CLIENT_SECRET = "ebay_client_secret"
        private const val KEY_FEE_PCT = "fee_pct"
        private const val KEY_MIN_PROFIT_PCT = "min_profit_pct"
        private const val KEY_TOP_N = "top_n"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
        private const val KEY_RUNAME = "ebay_runame"
        private const val KEY_WATCHLIST_ACCESS_TOKEN = "watchlist_access_token"
        private const val KEY_WATCHLIST_REFRESH_TOKEN = "watchlist_refresh_token"
        private const val KEY_WATCHLIST_EXPIRES_AT = "watchlist_token_expires_at"
    }
}

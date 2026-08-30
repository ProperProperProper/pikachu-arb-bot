package com.psa10arb.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Instant

private const val AUTHORIZE_URL = "https://auth.ebay.com/oauth2/authorize"
private const val TOKEN_URL = "https://api.ebay.com/identity/v1/oauth2/token"

// Trading API's AddToWatchList call accepts a modern OAuth user token via the
// X-EBAY-API-IAF-TOKEN header — this is the umbrella scope eBay's own docs use
// for that migration path (there is no narrower "watchlist" scope).
private const val USER_SCOPE = "https://api.ebay.com/oauth/api_scope"

// Loopback redirect (RFC 8252 "loopback interface redirection") — the same
// pattern ebay_card_search's own --auth flow already uses (EBAY_RUNAME
// pointing at http://localhost:8080/oauth/callback). Reusing an existing
// RuName from that setup works here unchanged.
private const val LOOPBACK_PORT = 8080
private const val LOOPBACK_PATH = "/oauth/callback"
const val LOOPBACK_REDIRECT_URI_HINT = "http://localhost:8080/oauth/callback"

/**
 * eBay user OAuth (authorization-code grant) — distinct from EbayAuth's
 * client-credentials app token. This one authorizes acting on the user's
 * real eBay account (AddToWatchList via the Trading API), so it needs its
 * own consent screen and its own RuName per developer account (stored in
 * Settings.ebayRuName — same as every other credential in this shared app,
 * nothing is baked in).
 */
class EbayUserAuth(private val settings: Settings, private val http: OkHttpClient) {

    fun buildConsentUrl(): String {
        val clientId = java.net.URLEncoder.encode(settings.clientId, "UTF-8")
        val redirect = java.net.URLEncoder.encode(settings.ebayRuName, "UTF-8")
        val scope = java.net.URLEncoder.encode(USER_SCOPE, "UTF-8")
        return "$AUTHORIZE_URL?client_id=$clientId&redirect_uri=$redirect&response_type=code&scope=$scope"
    }

    /**
     * Opens a tiny local HTTP server on 127.0.0.1:8080 and waits (up to 3
     * minutes) for eBay's redirect to hit it with ?code=... — mirrors the
     * desktop tool's own localhost:8080 callback, just running on-device
     * instead of on a PC. Call this concurrently with showing the consent
     * WebView. Returns the authorization code, or null on timeout/cancel.
     */
    suspend fun awaitAuthorizationCode(): String? = withContext(Dispatchers.IO) {
        var server: ServerSocket? = null
        try {
            server = ServerSocket(LOOPBACK_PORT)
            // accept() is a blocking (non-suspending) call, so coroutine
            // cancellation/withTimeoutOrNull can't interrupt it — bound it with a
            // real socket timeout instead, so a user who abandons the consent
            // screen doesn't leak a thread holding port 8080 forever.
            server.soTimeout = 180_000
            AppLogger.log("EbayUserAuth", "Loopback listener started on 127.0.0.1:$LOOPBACK_PORT")
            val deadline = System.currentTimeMillis() + 180_000L
            var code: String? = null
            while (code == null && System.currentTimeMillis() < deadline) {
                code = acceptOneRequest(server)
                // A request hit the server but wasn't the callback (or had no code) —
                // keep listening (soTimeout still bounds the overall wait).
            }
            code
        } catch (e: java.net.SocketTimeoutException) {
            AppLogger.log("EbayUserAuth", "Loopback listener timed out waiting for eBay redirect")
            null
        } catch (e: Exception) {
            AppLogger.log("EbayUserAuth", "Loopback listener error: ${e.message}")
            null
        } finally {
            try { server?.close() } catch (_: Exception) {}
        }
    }

    /** Accepts one connection, replies with a simple confirmation page, and
     * returns the "code" query param if this was the eBay redirect — null
     * otherwise (caller keeps listening). */
    private fun acceptOneRequest(server: ServerSocket): String? {
        val socket = server.accept()
        return socket.use { s ->
            val requestLine = s.getInputStream().bufferedReader().readLine() ?: return@use null
            // "GET /oauth/callback?code=...&expires_in=... HTTP/1.1"
            val target = requestLine.split(" ").getOrNull(1) ?: return@use null
            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" +
                "<html><body>Signed in — you can close this and return to the app.</body></html>"
            s.getOutputStream().write(response.toByteArray())
            s.getOutputStream().flush()
            if (!target.startsWith(LOOPBACK_PATH)) return@use null
            val query = target.substringAfter("?", "")
            val params = query.split("&").associate { kv ->
                val bits = kv.split("=", limit = 2)
                bits[0] to (bits.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: "")
            }
            params["code"]
        }
    }

    /** Exchanges the authorization code for a user access+refresh token pair. */
    suspend fun exchangeCode(code: String): Boolean = withContext(Dispatchers.IO) {
        val creds = android.util.Base64.encodeToString(
            "${settings.clientId}:${settings.clientSecret}".toByteArray(), android.util.Base64.NO_WRAP
        )
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", settings.ebayRuName)
            .build()
        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("Authorization", "Basic $creds")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(body)
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.log("EbayUserAuth", "Token exchange failed: HTTP ${resp.code}")
                    return@withContext false
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                storeTokens(json)
                AppLogger.log("EbayUserAuth", "Watchlist auth connected")
                true
            }
        } catch (e: Exception) {
            AppLogger.log("EbayUserAuth", "Token exchange error: ${e.message}")
            false
        }
    }

    /** Returns a valid user access token, refreshing via the stored refresh
     * token if the cached one is expired. Returns null if never connected or
     * the refresh token itself has been revoked/expired (needs reconnect). */
    suspend fun getValidUserToken(): String? = withContext(Dispatchers.IO) {
        val cached = settings.watchlistAccessToken
        if (!cached.isNullOrBlank() && Instant.now().epochSecond < settings.watchlistAccessTokenExpiresAt - 60) {
            return@withContext cached
        }
        val refresh = settings.watchlistRefreshToken ?: return@withContext null
        val creds = android.util.Base64.encodeToString(
            "${settings.clientId}:${settings.clientSecret}".toByteArray(), android.util.Base64.NO_WRAP
        )
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("scope", USER_SCOPE)
            .build()
        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("Authorization", "Basic $creds")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(body)
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.log("EbayUserAuth", "Watchlist token refresh failed: HTTP ${resp.code}")
                    return@withContext null
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                settings.watchlistAccessToken = json.optString("access_token")
                settings.watchlistAccessTokenExpiresAt = Instant.now().epochSecond + json.optLong("expires_in", 7200)
                settings.watchlistAccessToken
            }
        } catch (e: Exception) {
            AppLogger.log("EbayUserAuth", "Watchlist token refresh error: ${e.message}")
            null
        }
    }

    private fun storeTokens(json: JSONObject) {
        settings.watchlistAccessToken = json.optString("access_token")
        settings.watchlistRefreshToken = json.optString("refresh_token", settings.watchlistRefreshToken ?: "")
        settings.watchlistAccessTokenExpiresAt = Instant.now().epochSecond + json.optLong("expires_in", 7200)
    }
}

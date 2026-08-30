package com.psa10arb.app.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val TOKEN_URL = "https://api.ebay.com/identity/v1/oauth2/token"
private const val OAUTH_SCOPE = "https://api.ebay.com/oauth/api_scope"

/** OAuth client-credentials token, cached with expiry — mirrors load_token/fetch_token/get_token. */
class EbayAuth(private val settings: Settings, private val http: OkHttpClient) {

    private fun loadCachedToken(): String? {
        val token = settings.accessToken ?: return null
        val expiresAt = settings.tokenExpiresAt
        return if (System.currentTimeMillis() / 1000.0 < expiresAt - 60) token else null
    }

    private suspend fun fetchToken(): String = withContext(Dispatchers.IO) {
        val cid = settings.clientId
        val sec = settings.clientSecret
        if (cid.isBlank() || sec.isBlank()) {
            error("EBAY_CLIENT_ID / EBAY_CLIENT_SECRET not set — enter them in Settings")
        }
        val creds = Base64.encodeToString("$cid:$sec".toByteArray(), Base64.NO_WRAP)
        val body = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("scope", OAUTH_SCOPE)
            .build()
        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("Authorization", "Basic $creds")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(body)
            .build()

        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("OAuth token fetch failed: HTTP ${resp.code}")
            }
            val json = JSONObject(resp.body?.string() ?: "{}")
            val accessToken = json.getString("access_token")
            val expiresIn = json.optLong("expires_in", 7200L)
            val expiresAt = System.currentTimeMillis() / 1000.0 + expiresIn
            settings.accessToken = accessToken
            settings.tokenExpiresAt = expiresAt.toLong()
            AppLogger.log("EbayAuth", "New OAuth token fetched (expires in ${expiresIn}s)") // never log the token itself
            accessToken
        }
    }

    suspend fun getToken(): String {
        val cached = loadCachedToken()
        if (cached != null) {
            AppLogger.log("EbayAuth", "Token cache hit")
            return cached
        }
        return fetchToken()
    }
}

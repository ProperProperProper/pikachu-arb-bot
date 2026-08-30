package com.psa10arb.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val SEARCH_URL = "https://api.ebay.com/buy/browse/v1/item_summary/search"
private const val ITEM_URL = "https://api.ebay.com/buy/browse/v1/item"

/** Browse API GET with exponential backoff — mirrors _get() in ebay_card_search.py. */
class EbayClient(
    private val auth: EbayAuth,
    private val counter: ApiCounter,
    private val http: OkHttpClient,
) {
    suspend fun get(params: Map<String, String>): JSONObject {
        val url = SEARCH_URL.toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return executeWithRetry(url, params.toString())
    }

    /** Full item detail — needed for eBay's structured grade verification
     * (conditionDescriptors: Professional Grader / Grade / Certification
     * Number), which the lightweight search endpoint doesn't expose. Only
     * call this for the small set of candidates that already passed title
     * matching — calling it per search result would multiply API usage by
     * hundreds. */
    suspend fun getItem(itemId: String): JSONObject {
        val url = ITEM_URL.toHttpUrl().newBuilder()
            .addPathSegment(itemId)
            .build()
        return executeWithRetry(url, "itemId=$itemId")
    }

    private suspend fun executeWithRetry(url: HttpUrl, logLabel: String): JSONObject = withContext(Dispatchers.IO) {
        val token = auth.getToken()
        var backoff = 2000L
        repeat(5) { attempt ->
            delay(1000)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("X-EBAY-C-MARKETPLACE-ID", "EBAY_AU")
                .get()
                .build()

            val response = try {
                http.newCall(request).execute()
            } catch (e: Exception) {
                AppLogger.log("EbayClient", "Request failed (attempt ${attempt + 1}/5): ${e.message}  $logLabel")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(64000)
                return@repeat
            }

            response.use { resp ->
                val daily = counter.incCalls()
                AppLogger.log("EbayClient", "GET $url  HTTP ${resp.code}  daily=$daily")
                if (daily >= ApiCounter.DAILY_LIMIT) {
                    throw QuotaError("Daily quota reached ($daily/${ApiCounter.DAILY_LIMIT})")
                }
                if (resp.isSuccessful) {
                    return@withContext JSONObject(resp.body?.string() ?: "{}")
                }
                if (resp.code in intArrayOf(429, 500, 502, 503, 504)) {
                    // fall through to backoff below
                } else {
                    val bodySnippet = resp.body?.string()?.take(500)
                    AppLogger.log("EbayClient", "Unexpected HTTP ${resp.code}: $bodySnippet")
                    throw RuntimeException("Unexpected HTTP ${resp.code}: $bodySnippet")
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(64000)
        }
        throw RuntimeException("5 retries exhausted ($logLabel)")
    }
}

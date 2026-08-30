package com.psa10arb.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val FX_API_URL = "https://open.er-api.com/v6/latest/AUD"
private const val FX_CACHE_TTL_MILLIS = 60 * 60 * 1000L // 1 hour, mirrors forex_arb.py's FX_CACHE_TTL

// Same emergency fallback forex_arb.py uses if the live API is ever
// unreachable — approximate, but still better than refusing to price
// non-AUD listings at all.
private val FALLBACK_RATES = mapOf(
    "JPY" to 98.0, "USD" to 0.63, "EUR" to 0.58, "GBP" to 0.50, "KRW" to 880.0,
)

/**
 * Live AUD-based FX rates (1 AUD = rates[CCY] units of CCY) — a Kotlin port
 * of forex_arb.py's fetch_fx_rates()/to_aud(): a free, no-key API
 * (open.er-api.com), cached in-memory for an hour.
 *
 * Every search that requests AUD pricing (`priceCurrency:AUD`) gets almost
 * all listings back already in AUD, but eBay can't always convert every
 * cross-border listing — this converts the rare straggler instead of
 * silently dropping it (which is what every bot in this app used to do,
 * and is part of why fewer listings turned up here than in the desktop
 * Python tools).
 */
object FxRates {
    private var cachedRates: Map<String, Double>? = null
    private var cachedAt: Long = 0L

    /** Converts a price in any currency to AUD. Returns null only if the
     * currency is unrecognized (neither live nor fallback rates have it) —
     * callers should treat that as "can't safely price this one", not as
     * "zero it out". */
    suspend fun toAud(price: Double, currency: String, http: OkHttpClient): Double? {
        if (currency.equals("AUD", ignoreCase = true)) return price
        val rates = getRates(http)
        val rate = rates[currency.uppercase()] ?: return null
        if (rate <= 0.0) return null
        return price / rate
    }

    private suspend fun getRates(http: OkHttpClient): Map<String, Double> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedRates?.let { if (now - cachedAt < FX_CACHE_TTL_MILLIS) return@withContext it }

        val fetched = try {
            val request = Request.Builder().url(FX_API_URL).get().build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                val json = JSONObject(resp.body?.string() ?: "{}")
                val ratesObj = json.optJSONObject("rates") ?: throw RuntimeException("no rates field")
                val map = mutableMapOf<String, Double>()
                ratesObj.keys().forEach { k -> map[k] = ratesObj.optDouble(k, 0.0) }
                map
            }
        } catch (e: Exception) {
            AppLogger.log("FxRates", "Live FX fetch failed (${e.message}) — using fallback rates")
            FALLBACK_RATES
        }
        cachedRates = fetched
        cachedAt = now
        AppLogger.log("FxRates", "Rates refreshed: 1 AUD = ${fetched["JPY"]} JPY, ${fetched["USD"]} USD")
        fetched
    }
}

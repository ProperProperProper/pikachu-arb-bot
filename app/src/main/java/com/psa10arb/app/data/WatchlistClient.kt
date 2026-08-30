package com.psa10arb.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

private const val TRADING_URL = "https://api.ebay.com/ws/api.dll"
private const val TRADING_NS = "urn:ebay:apis:eBLBaseComponents"

/** Trading API AddToWatchList — ported from store_grader_japan.py's
 * watch_items(). Batches of 10 item IDs per call, IAF header carries the
 * modern OAuth user token (EbayUserAuth), matching the "oauth" branch of
 * the Python original's _creds_block/_trading_call. */
class WatchlistClient(private val clientId: String, private val http: OkHttpClient) {

    /** Adds item IDs to the signed-in user's real eBay watchlist. Returns
     * the subset actually confirmed added; never throws — failures are
     * logged and simply excluded from the result. */
    suspend fun addToWatchList(itemIds: List<String>, userToken: String): List<String> = withContext(Dispatchers.IO) {
        if (itemIds.isEmpty()) return@withContext emptyList()
        val added = mutableListOf<String>()
        for (batch in itemIds.chunked(10)) {
            val idsXml = batch.joinToString("\n") { "  <ItemID>$it</ItemID>" }
            val xml = """<?xml version="1.0" encoding="utf-8"?>
<AddToWatchListRequest xmlns="$TRADING_NS">
$idsXml
</AddToWatchListRequest>"""

            val request = Request.Builder()
                .url(TRADING_URL)
                .header("X-EBAY-API-SITEID", "0")
                .header("X-EBAY-API-COMPATIBILITY-LEVEL", "967")
                .header("X-EBAY-API-CALL-NAME", "AddToWatchList")
                .header("X-EBAY-API-APP-NAME", clientId)
                .header("X-EBAY-API-IAF-TOKEN", userToken)
                .header("Content-Type", "text/xml; charset=utf-8")
                .post(xml.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .build()

            try {
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        AppLogger.log("Watchlist", "AddToWatchList batch HTTP ${resp.code}")
                    } else {
                        val ack = parseAck(body)
                        if (ack == "Success" || ack == "Warning") {
                            added.addAll(batch)
                            AppLogger.log("Watchlist", "Batch of ${batch.size} added OK (Ack=$ack)")
                        } else {
                            AppLogger.log("Watchlist", "Batch failed (Ack=$ack)")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.log("Watchlist", "AddToWatchList batch error: ${e.message}")
            }
            delay(400)
        }
        added
    }

    private fun parseAck(xmlBody: String): String? {
        return try {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xmlBody)))
            val nodes = doc.getElementsByTagNameNS(TRADING_NS, "Ack")
            if (nodes.length == 0) return null
            (nodes.item(0) as Element).textContent
        } catch (e: Exception) {
            null
        }
    }
}

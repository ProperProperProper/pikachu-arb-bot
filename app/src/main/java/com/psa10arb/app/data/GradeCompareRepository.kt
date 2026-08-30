package com.psa10arb.app.data

import okhttp3.OkHttpClient
import org.json.JSONObject

private const val CATEGORY_ID = "183454" // CCG Individual Cards
private const val MAX_LANDED_AUD = 150.0
private val PCG10_RE = Regex("""\bPCG[\s\-]?10\b""", RegexOption.IGNORE_CASE)
private val CGC10_RE = Regex("""\bCGC[\s\-]?10\b""", RegexOption.IGNORE_CASE)
private val PSA10_RE = Regex("""\bPSA[\s\-]?10\b""", RegexOption.IGNORE_CASE)
private val JAPANESE_RE = Regex("""\bJapanese\b""", RegexOption.IGNORE_CASE)

/**
 * Buy side: the cheapest active PCG-10 Japanese-language listing per card,
 * across top-tier characters (Matching.TOP_TIER_CHARACTERS) — PCG is a
 * smaller/cheaper grading company, so a PCG-10 often undersells an
 * equivalent PSA-10 of the same card. Search itself is built around PCG
 * (query text + title regex both target PCG listings specifically), not
 * just a label on a generic "graded" search.
 *
 * Sell side: PSA/CGC only (matching the same-grader convention the rest of
 * this app uses everywhere else) — compares against the cheapest *current*
 * AU PSA-10 buy-it-now listing (no sold-listings scraping; see
 * ScanRepository's doc for why). A current CGC-10 listing is shown
 * alongside as bonus context (not part of the profit gate). Before picking
 * a reference price, matched current listings have extreme-outlier prices
 * excluded (PriceUtils.excludeExtremePrices).
 *
 * Raw/CV-graded cards are a separate, standalone bot (RawGradingSource) —
 * not merged in here.
 */
class GradeCompareRepository(private val client: EbayClient, private val http: OkHttpClient) {
    suspend fun runScan(
        feePct: Double,
        minProfitPct: Double,
        onProgress: (String) -> Unit = {},
    ): List<PcgCompareRow> {
        AppLogger.log("GradeCompareRepo", "runScan start feePct=$feePct minProfitPct=$minProfitPct")
        onProgress("Searching for cheap PCG-10 Japanese listings…")
        val listings = fetchCheapestPcgListings()
        AppLogger.log("GradeCompareRepo", "${listings.size} unique PCG-10 cards found")
        onProgress("${listings.size} PCG-10 candidates — checking current PSA/CGC AU listings…")

        val rows = mutableListOf<PcgCompareRow>()
        listings.entries.forEachIndexed { idx, (key, pcg) ->
            onProgress("Grader price lookup ${idx + 1}/${listings.size}: $key")

            // One current-listings fetch per card, reused across PSA/CGC filtering
            // below — no need to hit the API twice per card.
            val currentForCard = fetchAuGradedListings(key)
                .filter { Matching.sameCard(pcg.title, it.title) && it.currency == pcg.currency }

            val psaClean = PriceUtils.excludeExtremePrices(currentForCard.filter { PSA10_RE.containsMatchIn(it.title) }) { it.price }
            val psaAsking = psaClean.minByOrNull { it.price } ?: return@forEachIndexed

            val cgcClean = PriceUtils.excludeExtremePrices(currentForCard.filter { CGC10_RE.containsMatchIn(it.title) }) { it.price }
            val cgcAsking = cgcClean.minByOrNull { it.price }

            val netSell = psaAsking.price * (1 - feePct / 100)
            val profit = netSell - pcg.landedPrice
            val profitPct = if (pcg.landedPrice != 0.0) profit / pcg.landedPrice * 100 else 0.0
            if (profitPct < minProfitPct) return@forEachIndexed

            rows.add(
                PcgCompareRow(
                    itemId = pcg.itemId,
                    cardKey = key,
                    title = pcg.title,
                    source = pcg.source,
                    price = pcg.price,
                    shipping = pcg.shipping,
                    landedPrice = pcg.landedPrice,
                    currency = pcg.currency,
                    url = pcg.url,
                    psaAskingPrice = psaAsking.price,
                    psaAskingUrl = psaAsking.url,
                    cgcAskingPrice = cgcAsking?.price,
                    estFeePct = feePct,
                    estProfit = profit,
                    estProfitPct = profitPct,
                )
            )
        }

        rows.sortByDescending { it.estProfitPct }
        AppLogger.log("GradeCompareRepo", "runScan complete -> ${rows.size} rows >= ${minProfitPct}% profit")
        return rows
    }

    /** Current AU buy-it-now listings (any PSA/CGC 10) for the same card
     * key — the full matched set so the caller can filter by grader and
     * exclude outliers before picking a reference price. */
    private suspend fun fetchAuGradedListings(key: String): List<MarketListing> {
        val data = client.get(
            mapOf(
                "q" to Matching.keyToQuery(key),
                "category_ids" to CATEGORY_ID,
                "filter" to "buyingOptions:{FIXED_PRICE},itemLocationCountry:AU",
                "sort" to "price",
                "limit" to "20",
            )
        )
        val out = mutableListOf<MarketListing>()
        val items = data.optJSONArray("itemSummaries") ?: return out
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val title = item.optString("title", "")
            if (!Matching.GRADE10_RE.containsMatchIn(title) || Matching.cardKey(title) != key) continue
            if (Matching.LOT_RE.containsMatchIn(title) || Matching.FAKE_RE.containsMatchIn(title)) continue
            val priceObj = item.optJSONObject("price")
            val price = priceObj?.optString("value")?.toDoubleOrNull() ?: continue
            out.add(
                MarketListing(
                    price = price,
                    currency = priceObj.optString("currency", "AUD"),
                    title = title,
                    url = item.optString("itemWebUrl", ""),
                )
            )
        }
        return out
    }

    private suspend fun fetchCheapestPcgListings(): Map<String, PcgListing> {
        val cheapest = LinkedHashMap<String, PcgListing>()
        for (character in Matching.TOP_TIER_CHARACTERS) {
            val data = client.get(
                mapOf(
                    "q" to "$character Japanese PCG 10",
                    "category_ids" to CATEGORY_ID,
                    "filter" to "buyingOptions:{FIXED_PRICE},priceCurrency:AUD",
                    "sort" to "price",
                    "limit" to "200",
                )
            )
            val items = data.optJSONArray("itemSummaries") ?: continue
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val iid = item.optString("itemId", "")
                if (iid.isEmpty()) continue

                val title = item.optString("title", "")
                if (!PCG10_RE.containsMatchIn(title)) continue
                if (!JAPANESE_RE.containsMatchIn(title)) continue
                if (Matching.LOT_RE.containsMatchIn(title) || Matching.FAKE_RE.containsMatchIn(title) || Matching.NOT_CARD_RE.containsMatchIn(title)) continue
                val key = Matching.cardKey(title)
                if (key.isEmpty()) continue

                val priceObj = item.optJSONObject("price")
                val rawPrice = priceObj?.optString("value")?.toDoubleOrNull() ?: continue
                val rawCurrency = priceObj.optString("currency", "AUD")
                // Convert rather than drop — priceCurrency:AUD gets almost everything
                // back in AUD already, but the rare straggler (e.g. USD) is still a
                // real candidate.
                val price = FxRates.toAud(rawPrice, rawCurrency, http) ?: continue
                val currency = "AUD"

                var shipping = 0.0
                var shipNote = ""
                val shipOpts = item.optJSONArray("shippingOptions")
                if (shipOpts != null && shipOpts.length() > 0) {
                    val shipCost = shipOpts.getJSONObject(0).optJSONObject("shippingCost")
                    val shipRaw = shipCost?.optString("value")?.toDoubleOrNull()
                    if (shipCost != null && shipRaw != null) {
                        val shipCurrency = shipCost.optString("currency", rawCurrency)
                        val shipAud = FxRates.toAud(shipRaw, shipCurrency, http)
                        if (shipAud != null) shipping = shipAud else shipNote = "ship currency unconvertible — excluded"
                    } else {
                        shipNote = "ship cost unknown"
                    }
                } else {
                    shipNote = "no shipping info (calculated/pickup?)"
                }

                val landed = price + shipping
                if (landed >= MAX_LANDED_AUD) continue

                val existing = cheapest[key]
                if (existing == null || landed < existing.landedPrice) {
                    cheapest[key] = PcgListing(
                        itemId = iid,
                        cardKey = key,
                        title = title,
                        source = "PCG-10 (Japanese)",
                        price = price,
                        shipping = shipping,
                        shipNote = shipNote,
                        landedPrice = landed,
                        currency = currency,
                        url = item.optString("itemWebUrl", ""),
                    )
                }
            }
        }
        return cheapest
    }
}

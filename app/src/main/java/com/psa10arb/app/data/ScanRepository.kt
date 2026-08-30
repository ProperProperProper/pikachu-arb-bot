package com.psa10arb.app.data

import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

private const val CATEGORY_ID = "183454" // CCG Individual Cards
private val GRADERS = listOf("PSA 10", "CGC 10")
private const val AUCTION_WINDOW_HOURS = 5L
private const val MAX_AUCTION_LANDED_AUD = 150.0

/**
 * Ending-soon (within 5h) PSA10/CGC10 graded-slab auctions for top-tier
 * characters (see Matching.TOP_TIER_CHARACTERS), any seller, worldwide.
 * One query per character × grader.
 *
 * Sell side: the cheapest *current* AU buy-it-now listing for the same
 * card/grade (via the Browse API directly — no sold-listings scraping;
 * that data source needed a login-walled scrape that proved too
 * rate-limit-prone in practice). Before picking a reference price, matched
 * current listings have extreme-outlier prices excluded
 * (PriceUtils.excludeExtremePrices) so a single mispriced/troll listing
 * can't distort the comparison.
 *
 * Only rows at/above minProfitPct are surfaced. Raw/CV-graded cards are a
 * separate, standalone bot (RawGradingSource) — not merged in here.
 */
class ScanRepository(private val client: EbayClient) {
    suspend fun runScan(
        feePct: Double,
        minProfitPct: Double,
        onProgress: (String) -> Unit = {},
    ): List<ScanRow> {
        AppLogger.log("ScanRepo", "runScan start feePct=$feePct minProfitPct=$minProfitPct")
        onProgress("Searching for PSA10/CGC10 auctions ending within ${AUCTION_WINDOW_HOURS}h…")
        val auctions = fetchEndingSoonAuctions()
        AppLogger.log("ScanRepo", "${auctions.size} ending-soon graded auctions found")

        onProgress("${auctions.size} auctions — checking current AU buy-it-now prices…")
        val byKey = auctions.groupBy { it.cardKey }
        val rows = mutableListOf<ScanRow>()

        byKey.entries.forEachIndexed { idx, (key, listings) ->
            onProgress("AU price lookup ${idx + 1}/${byKey.size}: $key")
            val askingListings = fetchAuAskingListings(key)
            for (a in listings) {
                val matchedAsking = askingListings.filter { Matching.sameCard(a.title, it.title) && it.currency == a.currency }
                val clean = PriceUtils.excludeExtremePrices(matchedAsking) { it.price }
                // Cheapest surviving (non-outlier) current listing — an actual
                // shoppable comparison, not a computed average with no real URL.
                val referenceRef = clean.minByOrNull { it.price } ?: continue

                val landed = a.landedPrice
                val netSell = referenceRef.price * (1 - feePct / 100)
                val profit = netSell - landed
                val profitPct = if (landed != 0.0) profit / landed * 100 else 0.0
                if (profitPct < minProfitPct) continue

                // Title-text matching ("PSA 10" in the title) can be wrong —
                // sellers mistype, or mention a grade in passing without the
                // card actually being graded that. Confirm against eBay's
                // own structured item data before surfacing the row.
                val certNumber = verifyGrade(a.itemId, a.title)
                if (certNumber == null) {
                    AppLogger.log("ScanRepo", "Dropped (grade not confirmed via eBay item details): ${a.title.take(60)}")
                    continue
                }

                rows.add(
                    ScanRow(
                        itemId = a.itemId,
                        cardKey = key,
                        title = a.title,
                        source = "Auction (verified graded)",
                        price = a.currentBid,
                        shipping = a.shipping,
                        shipNote = a.shipNote,
                        landedPrice = landed,
                        currency = a.currency,
                        endTimeMillis = a.endTimeMillis,
                        listingUrl = a.url,
                        auReferencePrice = referenceRef.price,
                        auReferenceUrl = referenceRef.url,
                        certNumber = certNumber,
                        estFeePct = feePct,
                        estProfit = profit,
                        estProfitPct = profitPct,
                    )
                )
            }
        }

        rows.sortByDescending { it.estProfitPct }
        AppLogger.log("ScanRepo", "runScan complete -> ${rows.size} rows >= ${minProfitPct}% profit")
        return rows
    }

    private suspend fun fetchEndingSoonAuctions(): List<PsaAuction> {
        // Truncate to whole seconds — eBay's itemEndDate filter expects
        // "2018-11-14T07:47:48Z" exactly. ISO_INSTANT on an Instant with
        // nanos prints fractional seconds, which eBay silently rejects
        // (no error — it just drops the filter and returns everything).
        val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val until = now.plusSeconds(AUCTION_WINDOW_HOURS * 3600)
        val fmt = DateTimeFormatter.ISO_INSTANT
        val out = LinkedHashMap<String, PsaAuction>()

        for (character in Matching.TOP_TIER_CHARACTERS) {
            for (grader in GRADERS) {
                val data = client.get(
                    mapOf(
                        "q" to "$character $grader",
                        "category_ids" to CATEGORY_ID,
                        "filter" to "buyingOptions:{AUCTION},itemEndDate:[${fmt.format(now)}..${fmt.format(until)}]",
                        "limit" to "200",
                    )
                )
                val items = data.optJSONArray("itemSummaries") ?: continue
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val iid = item.optString("itemId", "")
                    if (iid.isEmpty() || out.containsKey(iid)) continue

                    val title = item.optString("title", "")
                    if (!Matching.GRADE10_RE.containsMatchIn(title)) continue
                    if (Matching.LOT_RE.containsMatchIn(title) || Matching.FAKE_RE.containsMatchIn(title) || Matching.NOT_CARD_RE.containsMatchIn(title)) continue
                    val key = Matching.cardKey(title)
                    if (key.isEmpty()) continue

                    // currentBidPrice reflects the live high bid; if no bids yet, fall
                    // back to the auction's starting "price".
                    val bidObj = item.optJSONObject("currentBidPrice") ?: item.optJSONObject("price")
                    val currentBid = bidObj?.optString("value")?.toDoubleOrNull() ?: continue
                    val currency = bidObj.optString("currency", "AUD")

                    var shipping = 0.0
                    var shipNote = ""
                    val shipOpts = item.optJSONArray("shippingOptions")
                    if (shipOpts != null && shipOpts.length() > 0) {
                        val shipCost = shipOpts.getJSONObject(0).optJSONObject("shippingCost")
                        val shipCurrency = shipCost?.optString("currency", currency) ?: currency
                        if (shipCurrency != currency) {
                            shipNote = "ship currency mismatch — excluded"
                        } else {
                            val parsed = shipCost?.optString("value")?.toDoubleOrNull()
                            if (parsed == null) shipNote = "ship cost unknown" else shipping = parsed
                        }
                    } else {
                        shipNote = "no shipping info (calculated/pickup?)"
                    }

                    val endTimeStr = item.optString("itemEndDate", "")
                    val endTimeMillis = try {
                        Instant.parse(endTimeStr).toEpochMilli()
                    } catch (e: Exception) {
                        until.toEpochMilli()
                    }
                    // Belt-and-braces: don't rely solely on the server-side
                    // itemEndDate filter (it fails silently rather than erroring —
                    // see the truncatedTo(SECONDS) note above) — re-check client-side too.
                    if (endTimeMillis > until.toEpochMilli() || endTimeMillis < now.toEpochMilli()) continue

                    val landed = currentBid + shipping
                    if (landed >= MAX_AUCTION_LANDED_AUD) continue

                    out[iid] = PsaAuction(
                        itemId = iid,
                        cardKey = key,
                        title = title,
                        currentBid = currentBid,
                        shipping = shipping,
                        shipNote = shipNote,
                        landedPrice = landed,
                        currency = currency,
                        endTimeMillis = endTimeMillis,
                        url = item.optString("itemWebUrl", ""),
                    )
                }
            }
        }
        return out.values.toList()
    }

    /** Current AU buy-it-now listings for the same card key, same grade
     * tier — the full matched set (not just the cheapest) so the caller can
     * exclude outliers before picking a reference price. */
    private suspend fun fetchAuAskingListings(key: String): List<MarketListing> {
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

    /**
     * Confirms via eBay's structured item detail (conditionDescriptors)
     * that this listing is really graded 10 by PSA or CGC — not just that
     * the title happens to say so. Returns the certification number (so
     * the user can look it up on PSA's/CGC's own site before bidding) if
     * confirmed, or null if the structured data disagrees with the title
     * or is missing.
     */
    private suspend fun verifyGrade(itemId: String, title: String): String? {
        val item = try {
            client.getItem(itemId)
        } catch (e: Exception) {
            AppLogger.log("ScanRepo", "Grade verification lookup failed for ${title.take(40)}: ${e.message}")
            return null
        }
        val descriptors = item.optJSONArray("conditionDescriptors") ?: return null
        var grader: String? = null
        var grade: String? = null
        for (i in 0 until descriptors.length()) {
            val d = descriptors.getJSONObject(i)
            val values = d.optJSONArray("values")
            val firstValue = values?.optJSONObject(0)?.optString("content")
            when (d.optString("name")) {
                "Professional Grader" -> grader = firstValue
                "Grade" -> grade = firstValue
            }
        }
        val graderOk = grader != null && (grader.contains("PSA", ignoreCase = true) || grader.contains("CGC", ignoreCase = true))
        if (!graderOk || grade != "10") {
            AppLogger.log("ScanRepo", "Grade mismatch (title said graded 10, item details say grader=$grader grade=$grade): ${title.take(60)}")
            return null
        }
        var cert: String? = null
        for (i in 0 until descriptors.length()) {
            val d = descriptors.getJSONObject(i)
            if (d.optString("name") == "Certification Number") {
                cert = d.optJSONArray("values")?.optJSONObject(0)?.optString("content")
            }
        }
        return cert ?: "unknown"
    }
}

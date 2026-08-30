package com.psa10arb.app.data

import com.psa10arb.app.grading.GradingPipeline
import okhttp3.OkHttpClient
import org.json.JSONObject

private const val CATEGORY_ID = "183454" // CCG Individual Cards
private val IMAGE_SIZE_RE = Regex("""s-l\d+""")
private const val MAX_SEARCH_PAGES = 15 // 15 x 200 = up to 3,000 listings per character, sorted cheapest first
private const val BATCH_SIZE = 50
private const val MAX_TOTAL_SCANNED = 900

/** A raw listing run through the CV grading pipeline. Grade 0 means the
 * grading attempt failed (download/decode error after retries) — still
 * surfaced so the listing/price is visible, just with no grade estimate. */
data class GradedRawCandidate(
    val itemId: String,
    val cardKey: String,
    val title: String,
    val price: Double,
    val shipping: Double,
    val landedPrice: Double,
    val currency: String,
    val url: String,
    val probableGrade: Int,
    val isHolo: Boolean,
    val limitingFactor: String,
)

/**
 * Standalone raw-card grading bot — Kotlin/OpenCV port of
 * store_grader_japan.py: searches worldwide for raw (ungraded) top-tier
 * -character listings (see Matching.TOP_TIER_CHARACTERS) restricted to a
 * sourcing country + landed-price cap, skips any listing with more than
 * one item available (photo may not be the actual card — same qty check
 * the Python tool does via the Browse API's estimatedAvailableQuantity),
 * then CV-grades each remaining photo (grading/GradingPipeline, a port of
 * grading_engine.py). No sold-price/profit comparison — this bot just
 * grades and lists (and, via WatchlistSeeder, auto-watches grade 10s),
 * same as the Python original.
 *
 * Scanning is batched: only the first BATCH_SIZE (50) not-yet-scanned
 * candidates are graded per call — grading is the slow, expensive part
 * (one photo download + CV pass at a time), so a search that turns up
 * hundreds of candidates doesn't turn one cycle into a multi-minute grind.
 * ScanViewModel calls this repeatedly (every 15 minutes while the bot is
 * running) to work through the backlog batch by batch. Every itemId graded
 * is remembered for the lifetime of this instance (i.e. until the app
 * restarts, same as every other bot's history) so nothing is ever scanned
 * twice, and the running total is capped at MAX_TOTAL_SCANNED (900) —
 * once hit, further calls return nothing new until the app restarts.
 */
class RawGradingSource(private val client: EbayClient, private val imageHttp: OkHttpClient) {

    private val scannedItemIds = LinkedHashSet<String>()

    suspend fun fetchGraded(
        locationCountry: String,
        maxLandedAud: Double,
        onProgress: (String) -> Unit = {},
    ): List<GradedRawCandidate> {
        if (scannedItemIds.size >= MAX_TOTAL_SCANNED) {
            AppLogger.log("RawGrading", "Scan cap reached (${scannedItemIds.size}/$MAX_TOTAL_SCANNED listings already scanned this session) — skipping")
            return emptyList()
        }

        val candidates = fetchRawCandidates(locationCountry, maxLandedAud)
        val unscanned = candidates.filterNot { it.itemId in scannedItemIds }
        val budget = MAX_TOTAL_SCANNED - scannedItemIds.size
        val batch = unscanned.take(minOf(BATCH_SIZE, budget))
        AppLogger.log(
            "RawGrading",
            "${candidates.size} raw candidates ($locationCountry, under \$$maxLandedAud AUD landed), " +
                "${unscanned.size} not yet scanned — grading batch of ${batch.size} " +
                "(${scannedItemIds.size}/$MAX_TOTAL_SCANNED scanned so far this session)",
        )

        val graded = mutableListOf<GradedRawCandidate>()
        var skippedQty = 0
        batch.forEachIndexed { idx, c ->
            scannedItemIds.add(c.itemId) // mark scanned regardless of outcome below

            onProgress("Checking ${idx + 1}/${batch.size}: ${c.title.take(40)}")
            if (!isSingleQty(c.itemId)) {
                skippedQty++
                AppLogger.log("RawGrading", "Multi-qty skip (photo may not be the actual card): ${c.title.take(60)}")
                return@forEachIndexed
            }

            onProgress("Grading ${idx + 1}/${batch.size}: ${c.title.take(40)}")
            val result = GradingPipeline.fetchAndGrade(c.imageUrl, imageHttp)
            if (result == null) {
                AppLogger.log("RawGrading", "Grade failed after retries (${GradingPipeline.takeLastFailureReason()}): ${c.title.take(60)}")
                return@forEachIndexed
            }
            AppLogger.log(
                "RawGrading",
                "Graded ${result.probableGrade} (cen=${result.centeringGrade} cor=${result.cornerGrade} edg=${result.edgeGrade} " +
                    "crease=${result.hasCrease} scratch=${result.hasScratch} surface=${result.surfaceFlagged} holo=${result.isHolo}): ${c.title.take(60)}",
            )
            // Only a predicted straight 10 makes the final list. crease/scratch/
            // surfaceFlagged are always false (all three detectors are disabled —
            // see GradingPipeline — after each false-positived on the large
            // majority of real card photos), so they're logged above for
            // visibility but no longer part of this gate.
            if (result.probableGrade != 10) return@forEachIndexed
            graded.add(
                GradedRawCandidate(
                    itemId = c.itemId,
                    cardKey = c.cardKey,
                    title = c.title,
                    price = c.price,
                    shipping = c.shipping,
                    landedPrice = c.landedPrice,
                    currency = c.currency,
                    url = c.url,
                    probableGrade = result.probableGrade,
                    isHolo = result.isHolo,
                    limitingFactor = result.limitingFactor,
                )
            )
        }
        if (skippedQty > 0) AppLogger.log("RawGrading", "$skippedQty listing(s) skipped (multi-qty)")
        AppLogger.log("RawGrading", "${graded.size}/${batch.size} predicted straight 10s this batch")
        return graded
    }

    /**
     * Returns true only if the listing has exactly 1 item available.
     * Uses the Browse API item-detail endpoint's estimatedAvailableQuantity
     * — the only reliable way to check this. Defaults to true (allow
     * through) if the lookup fails, same as the Python original.
     */
    private suspend fun isSingleQty(itemId: String): Boolean {
        val item = try {
            client.getItem(itemId)
        } catch (e: Exception) {
            AppLogger.log("RawGrading", "Qty check failed for $itemId: ${e.message}")
            return true
        }
        val avail = item.optJSONArray("estimatedAvailabilities") ?: return true
        if (avail.length() == 0) return true
        val qty = avail.getJSONObject(0).optInt("estimatedAvailableQuantity", 1)
        return qty == 1
    }

    /**
     * Faithful port of fetch_japan_listings()/is_raw_single() in
     * store_grader_japan.py. Two things this MUST match exactly, learned the
     * hard way from the Kotlin port finding far fewer cards than the Python
     * tool on the same search:
     *  1. Matching is just "does the title mention this character" — no
     *     requirement for a parseable card number. Matching.cardKey() (built
     *     for the arb bots, which need a precise key to cross-reference sold/
     *     asking prices) was wrongly reused here and silently dropped every
     *     listing whose title had no clean "#123"/"123/456" — most raw JP
     *     listings. This bot doesn't compare prices at all, so it never
     *     needed that requirement.
     *  2. Pagination: the Python tool pages up to MAX_SEARCH_PAGES x 200 =
     *     3,000 listings (stopping early once price exceeds the cap, since
     *     results are sorted cheapest-first) — the Kotlin port only ever
     *     fetched a single page of 200.
     * Also requesting `priceCurrency:AUD` explicitly (the Python filter does
     * too) rather than relying on eBay defaulting to AUD — without it,
     * cross-border JP listings can come back priced in USD/JPY and get
     * silently dropped by the currency guard below.
     */
    private suspend fun fetchRawCandidates(locationCountry: String, maxLandedAud: Double): List<RawCandidate> {
        val out = LinkedHashMap<String, RawCandidate>()
        for (character in Matching.TOP_TIER_CHARACTERS) {
            var offset = 0
            pageLoop@ for (page in 1..MAX_SEARCH_PAGES) {
                val data = client.get(
                    mapOf(
                        "q" to character,
                        "category_ids" to CATEGORY_ID,
                        "filter" to "itemLocationCountry:$locationCountry,priceCurrency:AUD,buyingOptions:{FIXED_PRICE},conditionIds:{4000}",
                        "sort" to "price",
                        "limit" to "200",
                        "offset" to offset.toString(),
                    )
                )
                val items = data.optJSONArray("itemSummaries") ?: break@pageLoop
                if (items.length() == 0) break@pageLoop

                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val iid = item.optString("itemId", "")
                    if (iid.isEmpty() || out.containsKey(iid)) continue

                    val title = item.optString("title", "")
                    if (!title.contains(character, ignoreCase = true)) continue
                    if (Matching.GRADED_RE.containsMatchIn(title)) continue // already graded — not raw
                    if (Matching.LOT_RE.containsMatchIn(title) || Matching.FAKE_RE.containsMatchIn(title) || Matching.NOT_CARD_RE.containsMatchIn(title)) continue

                    val priceObj = item.optJSONObject("price")
                    val rawPrice = priceObj?.optString("value")?.toDoubleOrNull() ?: continue
                    val rawCurrency = priceObj.optString("currency", "AUD")

                    // Ascending price sort is by each item's *native* price. Requesting
                    // priceCurrency:AUD gets almost everything back already in AUD, so
                    // this early-exit stays valid for the common case — but skip it for
                    // the rare non-AUD straggler (converted below), since its native
                    // value isn't comparable to the AUD-sorted order.
                    if (rawCurrency.equals("AUD", ignoreCase = true) && rawPrice > maxLandedAud) break@pageLoop

                    // Convert rather than drop — a listing priced in USD/JPY/etc is
                    // still a real candidate, and dropping it here (the old behavior)
                    // was a large part of why this bot found far fewer cards than the
                    // Python original.
                    val price = FxRates.toAud(rawPrice, rawCurrency, imageHttp) ?: continue
                    val currency = "AUD"

                    var shipping = 0.0
                    val shipOpts = item.optJSONArray("shippingOptions")
                    if (shipOpts != null && shipOpts.length() > 0) {
                        val shipCost = shipOpts.getJSONObject(0).optJSONObject("shippingCost")
                        val shipRaw = shipCost?.optString("value")?.toDoubleOrNull()
                        if (shipCost != null && shipRaw != null) {
                            val shipCurrency = shipCost.optString("currency", rawCurrency)
                            shipping = FxRates.toAud(shipRaw, shipCurrency, imageHttp) ?: 0.0
                        }
                    }
                    val landed = price + shipping
                    if (landed >= maxLandedAud) continue

                    // Browse API search results default to a 225px thumbnail (s-l225) —
                    // upscaling that to the grading engine's working resolution amplifies
                    // JPEG compression blocking into false creases/scratches. Request the
                    // full-size photo instead, same as the Python original.
                    var imageUrl = item.optJSONObject("image")?.optString("imageUrl", "") ?: ""
                    if (imageUrl.isEmpty()) continue
                    imageUrl = IMAGE_SIZE_RE.replace(imageUrl, "s-l1600")

                    out[iid] = RawCandidate(
                        itemId = iid,
                        cardKey = Matching.detectCharacter(title) ?: character,
                        title = title,
                        price = price,
                        shipping = shipping,
                        landedPrice = landed,
                        currency = currency,
                        url = item.optString("itemWebUrl", ""),
                        imageUrl = imageUrl,
                    )
                }

                if (items.length() < 200) break@pageLoop
                offset += 200
            }
        }
        return out.values.toList()
    }
}

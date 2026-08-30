package com.psa10arb.app.data

/**
 * An ending-soon PSA10/CGC10 graded-slab auction listing (any seller).
 * This is the buy side: cheap because it's an about-to-end auction.
 */
data class PsaAuction(
    val itemId: String,
    val cardKey: String,
    val title: String,
    val currentBid: Double,
    val shipping: Double,
    val shipNote: String,
    val landedPrice: Double,
    val currency: String,
    val endTimeMillis: Long,
    val url: String,
)

/** A current fixed-price (buy-it-now) listing — the "asking price" reference. */
data class MarketListing(
    val price: Double,
    val currency: String,
    val title: String,
    val url: String,
)

/**
 * An ending-soon, already-graded PSA10/CGC10 auction, matched against the
 * cheapest *current* AU buy-it-now listing for the same card/grade (no
 * sold-price scraping — see SoldPriceScraper's removal note in the repo
 * history; that data source required a login-walled scrape that proved too
 * rate-limit-prone). endTimeMillis + certNumber are always set (certNumber
 * confirmed via eBay's structured item data). auReferencePrice/-Url is
 * picked only after excluding extreme-outlier prices from the matched
 * current listings (PriceUtils.excludeExtremePrices) — never null once a
 * row is included, since a row with no comparable current listing is
 * dropped rather than shown.
 */
data class ScanRow(
    val itemId: String,
    val cardKey: String,
    val title: String,
    val source: String,
    val price: Double,
    val shipping: Double,
    val shipNote: String,
    val landedPrice: Double,
    val currency: String,
    val endTimeMillis: Long?,
    val listingUrl: String,
    val auReferencePrice: Double,
    val auReferenceUrl: String,
    val certNumber: String?,
    val estFeePct: Double,
    val estProfit: Double,
    val estProfitPct: Double,
)

class QuotaError(message: String) : Exception(message)

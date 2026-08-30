package com.psa10arb.app.data

/** A cheap active PCG-10 Japanese-language listing for a given card. */
data class PcgListing(
    val itemId: String,
    val cardKey: String,
    val title: String,
    val source: String,
    val price: Double,
    val shipping: Double,
    val shipNote: String,
    val landedPrice: Double,
    val currency: String,
    val url: String,
)

/** One matched row: a PCG-10 buy candidate priced against the cheapest
 * *current* AU PSA-10 buy-it-now listing for the same card (primary profit
 * driver, outlier-excluded — see PriceUtils.excludeExtremePrices), with a
 * current CGC-10 listing shown alongside as bonus context — same "extra
 * context, not part of the profit gate" convention as
 * ScanRow.auReferencePrice. */
data class PcgCompareRow(
    val itemId: String,
    val cardKey: String,
    val title: String,
    val source: String,
    val price: Double,
    val shipping: Double,
    val landedPrice: Double,
    val currency: String,
    val url: String,
    val psaAskingPrice: Double,
    val psaAskingUrl: String,
    val cgcAskingPrice: Double?,
    val estFeePct: Double,
    val estProfit: Double,
    val estProfitPct: Double,
)

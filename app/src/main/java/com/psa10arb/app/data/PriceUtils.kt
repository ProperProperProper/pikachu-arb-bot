package com.psa10arb.app.data

object PriceUtils {
    /**
     * Drops obviously-wrong outlier prices (listing typos, troll/joke
     * listings, misfiled items) before a comparison price is picked from a
     * set of matched current listings: anything more than 3x above or below
     * the median of the candidate set is excluded. With 0 or 1 candidates
     * there's nothing to compare against, so everything is kept as-is.
     */
    fun <T> excludeExtremePrices(items: List<T>, priceOf: (T) -> Double): List<T> {
        if (items.size <= 1) return items
        val sorted = items.map(priceOf).sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
        if (median <= 0.0) return items
        val low = median / 3.0
        val high = median * 3.0
        return items.filter { priceOf(it) in low..high }
    }
}

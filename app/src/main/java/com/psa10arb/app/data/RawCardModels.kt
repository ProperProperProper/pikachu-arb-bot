package com.psa10arb.app.data

/** A worldwide raw (ungraded) top-tier-character BIN listing under the
 * price cap, before grading. Used by RawGradingSource. */
data class RawCandidate(
    val itemId: String,
    val cardKey: String,
    val title: String,
    val price: Double,
    val shipping: Double,
    val landedPrice: Double,
    val currency: String,
    val url: String,
    val imageUrl: String,
)

package com.psa10arb.app.grading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

private const val GRADING_W = 630
private const val GRADING_H = 880

/**
 * Ported from grading_engine.py's run_psa_grading / grade_ebay_photo /
 * fetch_and_grade.
 *
 * Deliberately NOT ported: the 30-point inspection report
 * (_build_insp_points and the sat_std/sparkle_pct/corner-sharpness/
 * remediation-strings values that only ever feed it) and corner sharpness
 * (Harris response — "info only" in the Python source, never affects a
 * grade). This app only needs the pass/fail probable_grade + the surface
 * flags that affect it (crease capping), not the human-readable report —
 * that report has no reader here, since the arb feature just needs
 * probableGrade == 10.
 */
object GradingPipeline {

    fun runPsaGrading(bgrIn: Mat): GradingResult {
        val h = bgrIn.rows(); val w = bgrIn.cols()
        val bounds = GradingBounds.findCardBounds(bgrIn)
        val M = 4
        val y1 = max(0, bounds.y1 + M); val y2 = min(h, bounds.y2 - M)
        val x1 = max(0, bounds.x1 + M); val x2 = min(w, bounds.x2 - M)
        var card = if (x2 > x1 && y2 > y1) Mat(bgrIn, org.opencv.core.Rect(x1, y1, x2 - x1, y2 - y1)) else bgrIn

        val aligned = GradingBounds.fineAlign(card)
        if (aligned !== card && card !== bgrIn) card.release()
        card = aligned

        val small = Mat()
        Imgproc.resize(card, small, Size(GRADING_W.toDouble(), GRADING_H.toDouble()), 0.0, 0.0, Imgproc.INTER_LANCZOS4)
        if (card !== bgrIn) card.release()

        val centering = GradingCentering.measureCentering(small)
        val holo = GradingHolo.detectHolo(small)
        val corners = GradingCorners.analyzeCorners(small, holo.isHolo)
        val edges = GradingEdges.analyzeEdges(small)
        val printLines = GradingSurface.detectPrintLines(small) && !holo.isHolo
        // Disabled — same reasoning as hasScratch below. Diagnostic logging
        // against real eBay listing photos (this session) showed crease=true
        // firing on ~95% of photos even after widening the sample inset;
        // real Pokemon card art has plenty of legitimate high-contrast
        // straight edges (text boxes, borders, HP numbers, energy icons)
        // that this Hough-line + perpendicular-gradient heuristic reads as
        // creases. Without labeled ground-truth photos to recalibrate the
        // threshold against, a detector that fires on nearly everything is
        // worse than no detector.
        val hasCrease = false
        val hasWarp = GradingSurface.detectWarp(small) && !holo.isHolo
        val hasStain = GradingSurface.detectStain(small) && !holo.isHolo
        val hasScratch = false // disabled upstream too — too many false positives on real cards
        // Disabled — same reasoning as hasCrease/hasScratch above. Diagnostic
        // logging against real eBay listing photos (this session, via the raw
        // grading bot) showed surfaceFlagged=true firing on ~71% of photos
        // (printLines/hasStain false-positiving on legitimate card art/print
        // texture), which made the "unflagged straight 10" final-list filter
        // in RawGradingSource reject nearly everything found. Without labeled
        // ground-truth photos to recalibrate against, same call as before:
        // a detector that fires on most real cards is worse than none.
        val surfFlagged = false

        val (lp, rp) = centering.lr.split("/").map { it.toDouble() }
        val (tp, bp) = centering.tb.split("/").map { it.toDouble() }

        var prob = minOf(centering.grade, corners.grade, edges.grade)
        val factors = sortedSetOf<String>()
        if (centering.grade == prob) factors.add("centering")
        if (corners.grade == prob) factors.add("corners")
        if (edges.grade == prob) factors.add("edges")
        val limit = factors.joinToString(" + ")

        var surfGrade = min(
            10,
            max(
                1,
                10 - (if (printLines) 3 else 0) - (if (hasCrease) 2 else 0) -
                    (if (hasStain) 1 else 0) - (if (hasWarp) 2 else 0) - (if (hasScratch) 2 else 0),
            ),
        )
        if (hasCrease) {
            surfGrade = min(surfGrade, 6)
            prob = min(prob, 6)
        }

        small.release()

        return GradingResult(
            centeringGrade = centering.grade,
            cornerGrade = corners.grade,
            edgeGrade = edges.grade,
            probableGrade = prob,
            limitingFactor = limit,
            lrRatio = centering.lr,
            tbRatio = centering.tb,
            lrPcts = lp to rp,
            tbPcts = tp to bp,
            worstCornerPct = corners.worst,
            worstCornerPos = corners.worstPos,
            edgeDefects = edges.defects,
            isHolo = holo.isHolo,
            holoConfidence = holo.confidence,
            surfaceFlagged = surfFlagged,
            surfaceGrade = surfGrade,
            hasScratch = hasScratch,
            hasCrease = hasCrease,
            borderL = centering.borderL,
            borderR = centering.borderR,
            borderT = centering.borderT,
            borderB = centering.borderB,
            cornerWhitening = corners.whites,
            edgeZoneFlags = edges.zoneFlags,
        )
    }

    fun gradeEbayPhoto(bgr: Mat): GradingResult {
        val quad = GradingBounds.findBestQuad(bgr)
        val cardImg = if (quad != null) GradingBounds.rotateAndCrop(bgr, quad) else bgr
        val result = runPsaGrading(cardImg)
        if (cardImg !== bgr) cardImg.release()
        return result.copy(photoMode = true)
    }

    /**
     * Downloads and grades a listing photo. Retries transient failures
     * (network hiccups, eBay's image CDN briefly rate-limiting rapid
     * sequential requests — the grading loop has no delay between items
     * the way EbayClient does for Browse API calls) — a burst of dozens of
     * consecutive "download/decode error" log lines within the same
     * second, all for whatever character was being graded at that moment,
     * was traced to exactly this: no retry on image fetch.
     *
     * @return null only after retries are exhausted; the caller logs
     *   [lastFailureReason] for diagnosis.
     */
    suspend fun fetchAndGrade(url: String, http: OkHttpClient): GradingResult? {
        if (url.isBlank()) return null
        return withContext(Dispatchers.IO) {
            var backoffMs = 800L
            repeat(3) { attempt ->
                try {
                    val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                    http.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            lastFailureReason.set("HTTP ${resp.code}")
                            return@use
                        }
                        val bytes = resp.body?.bytes()
                        if (bytes == null) {
                            lastFailureReason.set("empty response body")
                            return@use
                        }
                        val bgr = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
                        if (bgr.empty()) {
                            lastFailureReason.set("image decode failed (${bytes.size} bytes)")
                            return@use
                        }
                        val result = gradeEbayPhoto(bgr)
                        bgr.release()
                        return@withContext result
                    }
                } catch (e: Exception) {
                    lastFailureReason.set(e.message ?: e.toString())
                }
                if (attempt < 2) {
                    kotlinx.coroutines.delay(backoffMs)
                    backoffMs *= 2
                }
            }
            null
        }
    }

    /** Set on every failed attempt inside fetchAndGrade; read by the caller
     * immediately after a null result to log why, without changing the
     * return type. Thread-confined to Dispatchers.IO per call via
     * withContext, but a plain field would race across concurrent calls —
     * use a ThreadLocal to stay safe if grading is ever parallelized. */
    private val lastFailureReason = ThreadLocal.withInitial { "unknown" }

    fun takeLastFailureReason(): String = lastFailureReason.get()
}

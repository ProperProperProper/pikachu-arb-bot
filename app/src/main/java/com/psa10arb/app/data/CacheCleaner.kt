package com.psa10arb.app.data

import android.content.Context
import java.io.File

private const val MAX_AGE_MILLIS = 8L * 60 * 60 * 1000 // 8 hours

/**
 * The grading pipeline (GradingPipeline/RawGradingSource) never writes
 * downloaded card photos to disk — they're decoded straight into an
 * in-memory OpenCV Mat, graded, and released. The only place image bytes
 * can land on disk at all is the WebView's own browser cache (from the
 * in-app eBay login screen), which Android manages under the app's cache
 * directory. This sweeps anything older than 8 hours out of there on every
 * app start, so cache size can't creep up over a long-running device.
 */
object CacheCleaner {
    fun sweep(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS
        val removed = deleteOlderThan(context.cacheDir, cutoff)
        if (removed > 0) AppLogger.log("CacheCleaner", "Removed $removed cache file(s) older than 8h")
    }

    private fun deleteOlderThan(dir: File, cutoffMillis: Long): Int {
        var count = 0
        val entries = dir.listFiles() ?: return 0
        for (entry in entries) {
            if (entry.isDirectory) {
                count += deleteOlderThan(entry, cutoffMillis)
            } else if (entry.lastModified() < cutoffMillis) {
                if (entry.delete()) count++
            }
        }
        return count
    }
}

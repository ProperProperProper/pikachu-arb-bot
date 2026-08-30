package com.psa10arb.app.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple append-only file logger, mirroring the .log files the Python tools
 * this app was ported from already write (ebay_card_search.log,
 * store_grader.log). Written to app-external-files so it's pullable via
 * `adb pull` without root: /sdcard/Android/data/com.psa10arb.app/files/psa10arb.log
 *
 * NEVER pass EBAY_CLIENT_SECRET, access tokens, or eBay session cookies to
 * log() — every call site in this app is expected to log query text,
 * counts, prices, titles, URLs, HTTP status codes, and error messages only.
 */
object AppLogger {
    private var logFile: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (logFile != null) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, "psa10arb.log")
        log("App", "Logger initialized -> ${logFile?.absolutePath}")
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val file = logFile ?: return
        try {
            file.appendText("${fmt.format(Date())}  ${tag.padEnd(12)}  $message\n")
        } catch (e: Exception) {
            // Best-effort only — a logging failure must never crash the app.
        }
    }
}

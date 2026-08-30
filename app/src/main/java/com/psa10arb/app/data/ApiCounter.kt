package com.psa10arb.app.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Local, on-device soft limit mirroring api_counter in ebay_card_search.py's
 * SQLite cache. This is advisory only — the real quota is enforced by eBay
 * against the developer key itself, shared across every device/tool using it.
 */
class ApiCounter(context: Context) {

    private val prefs = context.getSharedPreferences("psa10arb_api_counter", Context.MODE_PRIVATE)

    private fun todayUtc(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    fun getCallCount(): Int {
        val day = todayUtc()
        return if (prefs.getString(KEY_DAY, null) == day) {
            prefs.getInt(KEY_CALLS, 0)
        } else {
            0
        }
    }

    fun incCalls(): Int {
        val day = todayUtc()
        val current = getCallCount()
        val next = current + 1
        prefs.edit().putString(KEY_DAY, day).putInt(KEY_CALLS, next).apply()
        return next
    }

    companion object {
        const val DAILY_LIMIT = 4500
        private const val KEY_DAY = "day"
        private const val KEY_CALLS = "calls"
    }
}

package com.psa10arb.app

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * In-app eBay consent screen for the watchlist feature (authorization-code
 * OAuth — the only eBay login flow this app has). The app never sees the
 * user's password here — eBay's own consent page runs inside this WebView,
 * and only the resulting authorization code (captured by ScanViewModel's
 * on-device loopback listener) ever reaches
 * this app.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistLoginScreen(consentUrl: String, waitingForRedirect: Boolean, onCancel: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Connect eBay watchlist") })
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Sign in and approve access below. This lets the app add matching " +
                    "cards straight to your real eBay watchlist — it never sees your " +
                    "password, only the authorization eBay hands back.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
            if (waitingForRedirect) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                    Text("Waiting for eBay to confirm…", style = MaterialTheme.typography.bodySmall)
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        loadUrl(consentUrl)
                    }
                },
            )
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}

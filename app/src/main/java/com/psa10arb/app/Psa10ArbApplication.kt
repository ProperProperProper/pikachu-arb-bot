package com.psa10arb.app

import android.app.Application
import android.util.Log
import com.psa10arb.app.data.AppLogger
import com.psa10arb.app.data.CacheCleaner
import org.opencv.android.OpenCVLoader

class Psa10ArbApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        CacheCleaner.sweep(this)
        val ok = OpenCVLoader.initLocal()
        Log.i("Psa10Arb", "OpenCV init: $ok")
        AppLogger.log("App", "OpenCV init: $ok")
    }
}

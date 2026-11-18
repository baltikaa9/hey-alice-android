package io.github.serifdrms.heyclaude

import android.app.Application
import io.github.serifdrms.heyclaude.shizuku.ShizukuBridge

class HeyAliceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuBridge.init(this)
    }
}

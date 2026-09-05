package com.lilac.anime

import android.app.Application

class LilacApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
    }
}

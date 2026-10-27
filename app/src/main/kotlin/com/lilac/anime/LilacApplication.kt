package com.lilac.anime

import com.lilac.anime.data.*

import android.app.Application

class LilacApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
        // Re:Anime catalog updates run independently of the UI process.
        // WorkManager decides the exact execution time under Android's
        // background limits; the requested cadence is 6 hours.
        ReAnimeCatalogScheduler.ensureScheduled(this)
        com.lilac.anime.network.CloudflareSession.init(this)
    }
}

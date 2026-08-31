package com.lilac.anime // 본인의 패키지명에 맞게 수정하세요

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import java.io.File
import java.util.concurrent.Executors

@UnstableApi
class LilacApplication : Application() {

    // 앱 어디서든 다운로드 매니저와 캐시에 접근할 수 있도록 Companion Object로 선언
    companion object {
        lateinit var databaseProvider: DatabaseProvider
        lateinit var downloadCache: Cache
        lateinit var dataSourceFactory: HttpDataSource.Factory
        lateinit var downloadManager: DownloadManager
    }

    override fun onCreate() {
        super.onCreate()

        // 1. 다운로드 정보 저장을 위한 데이터베이스
        databaseProvider = StandaloneDatabaseProvider(this)

        // 2. 영상 조각들이 실제로 저장될 캐시 폴더 설정
        val cacheDir = File(getExternalFilesDir(null), "lilac_downloads")
        downloadCache = SimpleCache(
            cacheDir,
            NoOpCacheEvictor(), // 다운로드된 파일이 자동으로 삭제되지 않도록 설정
            databaseProvider
        )

        // 3. 403 에러 우회를 위한 HTTP 헤더 설정 (기존 플레이어에 넣었던 것과 동일)
        dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36",
                    "Referer" to "https://play.sub3.top/",
                    "Origin" to "https://play.sub3.top"
                )
            )

        // 4. 다운로드 매니저 초기화
        downloadManager = DownloadManager(
            this,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Executors.newFixedThreadPool(6) // 동시 다운로드 스레드 수
        )
    }
}
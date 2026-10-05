package com.lilac.anime.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lilac.anime.AppContextHolder
import com.lilac.anime.data.offline.OfflineStore

/**
 * Periodically checks Re:Anime for newly added titles without crawling the
 * entire catalog. WorkManager controls the actual background execution time;
 * the 6-hour interval is the minimum desired cadence, not an exact wall clock.
 */
class ReAnimeCatalogRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val repository = AnimeRepository()

    override suspend fun doWork(): Result {
        val context = applicationContext
        AppContextHolder.init(context)

        return try {
            if (!OfflineStore.isAnimeListCacheFresh(context, "reanime")) {
                val cached = OfflineStore.getSavedAnimeList(context, "reanime")
                val cachedIds = cached.asSequence().map { it.id }.toHashSet()

                val added = repository.refreshReAnimeCatalogIncremental(cachedIds)
                if (added.isNotEmpty()) {
                    OfflineStore.mergeAnimeListCache(
                        context,
                        added,
                        source = "reanime",
                        markFresh = true,
                        newFirst = true
                    )
                    Log.i(
                        "ReAnimeCatalogWorker",
                        "REFRESH_SUCCESS added=${added.size} cachedBefore=${cached.size}"
                    )
                } else {
                    // Even when no titles were added, record the successful
                    // check so another full check is not scheduled immediately.
                    OfflineStore.saveAnimeList(
                        context,
                        cached,
                        source = "reanime",
                        markFresh = true
                    )
                    Log.i(
                        "ReAnimeCatalogWorker",
                        "REFRESH_SUCCESS added=0 cached=${cached.size}"
                    )
                }
            } else {
                Log.d("ReAnimeCatalogWorker", "SKIP_FRESH")
            }
            Result.success()
        } catch (t: Throwable) {
            Log.w("ReAnimeCatalogWorker", "REFRESH_FAILED", t)
            Result.retry()
        }
    }
}

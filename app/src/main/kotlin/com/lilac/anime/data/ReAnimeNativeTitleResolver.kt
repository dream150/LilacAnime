package com.lilac.anime.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fallback native-title lookup for Re:ANIME entries whose search payload only
 * contains the display/English title. The AniList ID itself comes from the
 * Re:ANIME response, so this only fills the missing native title metadata.
 */
object ReAnimeNativeTitleResolver {
    private const val TAG = "ReAnimeNativeTitle"
    private const val URL = "https://graphql.anilist.co"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(anilistId: Int?): String? = withContext(Dispatchers.IO) {
        if (anilistId == null || anilistId <= 0) return@withContext null

        runCatching {
            val query = "query(\$id:Int!){Media(id:\$id){title{native}}}"
            val body = JSONObject()
                .put("query", query)
                .put("variables", JSONObject().put("id", anilistId))
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(URL)
                .post(body)
                .header("Accept", "application/json")
                .header("User-Agent", "LilacAnime/ReAnimeNativeTitle")
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "LOOKUP_FAILED id=$anilistId code=${response.code}")
                    return@use null
                }

                val native = JSONObject(text)
                    .optJSONObject("data")
                    ?.optJSONObject("Media")
                    ?.optJSONObject("title")
                    ?.optString("native")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                Log.d(TAG, "LOOKUP id=$anilistId native=[$native]")
                native
            }
        }.getOrElse {
            Log.w(TAG, "LOOKUP_ERROR id=$anilistId", it)
            null
        }
    }
}

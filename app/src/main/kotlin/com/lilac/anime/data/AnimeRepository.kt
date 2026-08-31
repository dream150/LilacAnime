package com.lilac.anime.data

import com.lilac.anime.Anime
import com.lilac.anime.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class AnimeRepository {

    private val client = LinkkfClient()

    companion object {
        private const val BASE_URL = "https://linkkf.tv"
        private const val LIST_URL = "$BASE_URL/list/2/"
        private const val BATCH_SIZE = 5
        // MAX_LIST_PAGES와 MAX_EMPTY_PAGES는 무제한 탐색 및 즉시 종료 조건으로 변경되어 제거되었습니다.
    }

    // 💡 홈 화면 전용: 1페이지만 빠르게 로드 (0.5초 소요)
    suspend fun getHomeAnimeList(): List<Anime> {
        val document = client.getDocument(LIST_URL)
        return LinkkfParser.parseAnimeList(document)
    }

    // 💡 전체 보기 탭 전용: 1페이지부터 순차적 백그라운드 수신
    fun getAllAnimeListFlow(): Flow<List<Anime>> = flow {
        val result = LinkedHashMap<String, Anime>()
        var batchStart = 1
        var shouldStop = false

        while (!shouldStop) {
            val batchEnd = batchStart + BATCH_SIZE - 1
            val pageRange = batchStart..batchEnd

            val pageResults = coroutineScope {
                pageRange.map { page ->
                    async(Dispatchers.IO) {
                        val url = if (page == 1) LIST_URL else "$LIST_URL" + "page/$page/"
                        try {
                            val document = client.getDocument(url)
                            val pageAnime = LinkkfParser.parseAnimeList(document)
                            page to pageAnime
                        } catch (_: Exception) {
                            // LinkkfClient already retries transient LTE/5G failures.
                            // A final failure is kept as an empty page here so one
                            // unavailable page does not crash the whole flow.
                            page to emptyList<Anime>()
                        }
                    }
                }.awaitAll().sortedBy { it.first }
            }

            for ((_, animeList) in pageResults) {
                if (animeList.isEmpty()) {
                    // 애니가 하나도 없는 페이지가 나오면 즉시 탐색 종료
                    shouldStop = true
                    break
                } else {
                    animeList.forEach { anime ->
                        result[anime.id] = anime
                    }
                }
            }

            if (result.isNotEmpty()) {
                emit(result.values.toList())
            }

            batchStart += BATCH_SIZE
        }
    }.flowOn(Dispatchers.IO)

    suspend fun getAnimeDetail(anime: Anime): Anime {
        val document = client.getDocument(anime.detailUrl)
        val parsedDetail = LinkkfParser.parseAnimeDetail(document, anime)

        return parsedDetail.copy(
            episodes = parsedDetail.episodes.ifEmpty { anime.episodes },
            dubEpisodes = parsedDetail.dubEpisodes.ifEmpty { anime.dubEpisodes }
        )
    }

    suspend fun getEpisodes(anime: Anime): List<Episode> {
        val document = client.getDocument(anime.detailUrl)
        return LinkkfParser.parseEpisodes(document, anime)
    }

    suspend fun getDubEpisodes(anime: Anime): List<Episode> {
        val document = client.getDocument(anime.detailUrl)
        return LinkkfParser.parseDubEpisodes(document, anime)
    }
}
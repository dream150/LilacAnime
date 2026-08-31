package com.lilac.anime.data.subtitle

sealed class KairanSubtitleResult {
    data class DirectFile(val path: String) : KairanSubtitleResult()
}

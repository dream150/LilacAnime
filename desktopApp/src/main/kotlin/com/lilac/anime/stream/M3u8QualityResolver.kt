package com.lilac.anime.stream

object M3u8QualityResolver {
    fun resolve(urls: List<String>): List<StreamQuality> {
        return urls.distinct().map { url ->
            StreamQuality(if (url.contains("/sd/")) "720p" else "1080p", url)
        }.groupBy { it.label }.flatMap { (label, streams) ->
            if (streams.size > 1) streams.mapIndexed { index, stream ->
                stream.copy(label = "$label #${index + 1}")
            } else streams
        }
    }
}

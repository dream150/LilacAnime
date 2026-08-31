package com.lilac.anime.desktop

object MvpPlayer {
    fun play(url: String) {
        if (url.isBlank()) return
        try {
            ProcessBuilder(
                "mpv",
                "--hwdec=auto",
                "--force-window=yes",
                url
            ).start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

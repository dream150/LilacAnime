package com.lilac.anime.data.subtitle

object KairanTitleNormalizer {
    fun normalize(title: String): String {
        val hangul = Regex("[가-힣]+")
            .findAll(title)
            .joinToString("") { it.value }

        return if (hangul.length >= 2) {
            hangul
        } else {
            title.replace(Regex("[^a-zA-Z0-9\\s]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}

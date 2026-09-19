package com.lilac.anime.data.subtitle

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
import com.lilac.anime.network.*
import com.lilac.anime.player.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.detail.*
import com.lilac.anime.ui.home.*
import com.lilac.anime.ui.navigation.*
import com.lilac.anime.ui.search.*
import com.lilac.anime.ui.settings.*
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

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

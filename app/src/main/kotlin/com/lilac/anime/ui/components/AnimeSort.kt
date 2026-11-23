package com.lilac.anime.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lilac.anime.Anime
import com.lilac.anime.viewmodel.AnimeViewModel
import java.text.Collator
import java.util.Locale

/** 작품 목록 정렬 기준. [key] 는 저장용이므로 바꾸지 말 것. */
enum class AnimeSort(val key: String, val label: String, val shortLabel: String) {
    /** 목록이 원래 가지고 있는 순서(탐색: 불러온 순서, 다운로드: 기존 순서). */
    DEFAULT("default", "기본 순서", "기본"),
    /** 내 목록에 담은 순서대로(오래 담은 것이 먼저). 지금까지의 보관함 순서와 같다. */
    ADDED_ASC("added_asc", "추가한 순", "추가순"),
    ADDED_DESC("added_desc", "최근 추가순", "최근 추가"),
    RECENT_WATCHED("recent_watched", "최근 시청순", "최근 시청"),
    TITLE_ASC("title_asc", "이름순 (가나다 · A→Z)", "이름순"),
    TITLE_DESC("title_desc", "이름 역순 (Z→A · 하→가)", "이름 역순"),
    NEWEST("newest", "최신 방영순", "최신순"),
    OLDEST("oldest", "오래된 방영순", "오래된순");

    companion object {
        fun fromKey(key: String?): AnimeSort? = values().firstOrNull { it.key == key }
    }
}

/** 화면별로 쓸 수 있는 정렬 기준과 기본값. 선택한 값은 화면(scope)마다 따로 기억한다. */
enum class SortScope(val key: String, val options: List<AnimeSort>, val default: AnimeSort) {
    EXPLORE(
        "explore",
        listOf(AnimeSort.DEFAULT, AnimeSort.TITLE_ASC, AnimeSort.TITLE_DESC, AnimeSort.NEWEST, AnimeSort.OLDEST),
        AnimeSort.DEFAULT
    ),
    LIBRARY(
        "library",
        listOf(
            AnimeSort.ADDED_DESC, AnimeSort.ADDED_ASC, AnimeSort.RECENT_WATCHED,
            AnimeSort.TITLE_ASC, AnimeSort.TITLE_DESC, AnimeSort.NEWEST, AnimeSort.OLDEST
        ),
        AnimeSort.ADDED_ASC
    ),
    DOWNLOADS(
        "downloads",
        listOf(
            AnimeSort.DEFAULT, AnimeSort.RECENT_WATCHED,
            AnimeSort.TITLE_ASC, AnimeSort.TITLE_DESC, AnimeSort.NEWEST, AnimeSort.OLDEST
        ),
        AnimeSort.DEFAULT
    )
}

/** 현재 선택된 정렬. 저장된 값이 없거나 이 화면에서 쓸 수 없는 값이면 기본값. (스냅샷 상태를 읽으므로 Compose 에서 바로 갱신된다.) */
fun AnimeViewModel.sortFor(scope: SortScope): AnimeSort =
    AnimeSort.fromKey(sortSelections[scope.key])?.takeIf { it in scope.options } ?: scope.default

fun AnimeViewModel.chooseSort(context: Context, scope: SortScope, sort: AnimeSort) =
    setSortSelection(context, scope.key, sort.key)

/** 시청 기록(최신이 앞)에서 작품별 최근 시청 순위. 작을수록 최근. */
fun AnimeViewModel.watchRanks(): Map<String, Int> {
    val ranks = HashMap<String, Int>()
    watchHistory.forEachIndexed { index, progress -> ranks.putIfAbsent(progress.animeId, index) }
    return ranks
}

/**
 * [list] 를 [sort] 기준으로 정렬한다. 항상 안정 정렬이고, 기준 값이 없는 작품(방영일 미상, 시청 기록 없음)은
 * 방향과 상관없이 맨 뒤에 원래 순서대로 둔다.
 */
fun sortAnime(list: List<Anime>, sort: AnimeSort, watchRanks: Map<String, Int> = emptyMap()): List<Anime> {
    return when (sort) {
        AnimeSort.DEFAULT, AnimeSort.ADDED_ASC -> list
        AnimeSort.ADDED_DESC -> list.asReversed().toList()
        AnimeSort.TITLE_ASC -> sortByTitle(list, descending = false)
        AnimeSort.TITLE_DESC -> sortByTitle(list, descending = true)
        AnimeSort.NEWEST -> sortByKnown(list, { airedKey(it).takeIf { k -> k > 0L } }, descending = true)
        AnimeSort.OLDEST -> sortByKnown(list, { airedKey(it).takeIf { k -> k > 0L } }, descending = false)
        // 순위는 작을수록 최근이므로 오름차순이 곧 최근 시청순이다.
        AnimeSort.RECENT_WATCHED -> sortByKnown(list, { watchRanks[it.id]?.toLong() }, descending = false)
    }
}

private fun sortByKnown(list: List<Anime>, key: (Anime) -> Long?, descending: Boolean): List<Anime> {
    val known = ArrayList<Pair<Long, Anime>>()
    val unknown = ArrayList<Anime>()
    for (anime in list) {
        val k = key(anime)
        if (k == null) unknown.add(anime) else known.add(k to anime)
    }
    val sorted = if (descending) known.sortedByDescending { it.first } else known.sortedBy { it.first }
    return sorted.map { it.second } + unknown
}

private val collator: Collator = Collator.getInstance(Locale.KOREAN).apply { strength = Collator.PRIMARY }

/** "[작품명]", "「작품명」" 처럼 기호로 시작하는 제목이 맨 앞으로 몰리지 않게 첫 글자/숫자부터 비교한다. */
private fun titleKey(anime: Anime): String {
    val title = anime.title.trim()
    return title.dropWhile { !it.isLetterOrDigit() }.ifEmpty { title }
}

private fun sortByTitle(list: List<Anime>, descending: Boolean): List<Anime> {
    // 비교할 때마다 키를 만들지 않도록 한 번만 계산해 둔다.
    val keyed = list.map { titleKey(it) to it }
    val cmp = Comparator<Pair<String, Anime>> { a, b ->
        val c = collator.compare(a.first, b.first)
        if (c != 0) c else a.second.id.compareTo(b.second.id)
    }
    return keyed.sortedWith(if (descending) cmp.reversed() else cmp).map { it.second }
}

private val fullDate = Regex("""(\d{4})\D+(\d{1,2})\D+(\d{1,2})""")
private val yearMonth = Regex("""(\d{4})\D+(\d{1,2})(?!\d)""")
private val yearOnly = Regex("""(?<!\d)(\d{4})(?!\d)""")

/**
 * 방영 시기를 yyyymmdd 형태의 숫자로 바꾼다. 알 수 없으면 0.
 * airedDate("2024-10-05", "2024.10", "2024") → seasonYear/year + season 순으로 가장 구체적인 값을 쓴다.
 */
internal fun airedKey(anime: Anime): Long {
    val text = anime.airedDate.trim()
    fullDate.find(text)?.let { m ->
        val (y, mo, d) = m.destructured
        if (y.toInt() in 1900..2100) return y.toLong() * 10_000 + mo.toLong().coerceIn(0, 12) * 100 + d.toLong().coerceIn(0, 31)
    }
    yearMonth.find(text)?.let { m ->
        val (y, mo) = m.destructured
        if (y.toInt() in 1900..2100) return y.toLong() * 10_000 + mo.toLong().coerceIn(0, 12) * 100
    }
    val year = yearOnly.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        ?: anime.seasonYear?.takeIf { it in 1900..2100 }
        ?: yearOnly.find(anime.year)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        ?: return 0L
    return year.toLong() * 10_000 + seasonMonth(anime.season) * 100L
}

private fun seasonMonth(season: String): Int {
    val s = season.lowercase(Locale.ROOT)
    return when {
        "winter" in s || "겨울" in s -> 1
        "spring" in s || "봄" in s -> 4
        "summer" in s || "여름" in s -> 7
        "fall" in s || "autumn" in s || "가을" in s -> 10
        else -> 0
    }
}

/** 헤더 오른쪽에 두는 정렬 버튼. 누르면 정렬 기준 메뉴가 열린다. */
@Composable
fun SortMenuButton(
    selected: AnimeSort,
    options: List<AnimeSort>,
    onSelect: (AnimeSort) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Filled.SwapVert, contentDescription = "정렬", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(selected.shortLabel, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = {
                        if (option == selected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        else Spacer(Modifier.size(20.dp))
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

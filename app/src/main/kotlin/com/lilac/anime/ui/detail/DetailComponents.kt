package com.lilac.anime.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lilac.anime.Anime
import com.lilac.anime.Episode
import com.lilac.anime.ui.AnimeImage
import com.lilac.anime.ui.components.lilacClickable

// ============================================================================
// 상단 히어로
// ============================================================================

/**
 * 백드롭 위에 포스터·제목·메타 정보를 얹은 상단 영역.
 * 아래쪽은 배경색으로 서서히 녹아들어서 본문과 자연스럽게 이어진다.
 */
@Composable
internal fun DetailHero(
    anime: Anime,
    episodeCount: Int,
    onBack: () -> Unit,
    onPosterClick: () -> Unit
) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().height(380.dp)) {
        AnimeImage(
            model = anime.backdrop.ifBlank { anime.poster },
            contentDescription = anime.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.45f),
                        0.30f to Color.Transparent,
                        0.62f to background.copy(alpha = 0.70f),
                        1f to background
                    )
                )
        )

        FilledIconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(start = 12.dp, top = 8.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.42f),
                contentColor = Color.White
            )
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "뒤로가기")
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            AnimeImage(
                model = anime.poster.ifBlank { anime.backdrop },
                contentDescription = anime.title,
                modifier = Modifier
                    .size(width = 112.dp, height = 164.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onPosterClick),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                Text(
                    text = anime.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(
                    "${episodeCount}화".takeIf { episodeCount > 0 },
                    anime.format.takeIf { it.isNotBlank() },
                    anime.year.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (anime.note.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = anime.note,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun GenreChips(genres: List<String>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(genres) { genre ->
            Text(
                text = genre,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

// ============================================================================
// 재생 / 보관 / 전체 저장
// ============================================================================

@Composable
internal fun DetailActions(
    playLabel: String,
    canPlay: Boolean,
    onPlay: () -> Unit,
    saved: Boolean,
    onToggleSaved: () -> Unit,
    showBatch: Boolean,
    isBatchDownloading: Boolean,
    batchIndex: Int,
    batchTotal: Int,
    onBatchClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onPlay,
                enabled = canPlay,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(playLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            FilledTonalIconButton(onClick = onToggleSaved, modifier = Modifier.size(52.dp)) {
                Icon(
                    imageVector = if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (saved) "내 목록에서 빼기" else "내 목록에 담기",
                    tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            if (showBatch) {
                FilledTonalIconButton(onClick = onBatchClick, modifier = Modifier.size(52.dp)) {
                    Icon(
                        imageVector = if (isBatchDownloading) Icons.Default.Close else Icons.Default.DownloadForOffline,
                        contentDescription = if (isBatchDownloading) "전체 저장 취소" else "전체 저장",
                        tint = if (isBatchDownloading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        if (isBatchDownloading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (batchTotal > 0) batchIndex.toFloat() / batchTotal else 0f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "전체 저장 중 $batchIndex / $batchTotal · 버튼을 누르면 취소돼요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun ViewStatsCard(day: Int, week: Int, month: Int, total: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DetailStat("오늘", day)
            DetailStat("이번 주", week)
            DetailStat("이번 달", month)
            DetailStat("전체", total)
        }
    }
}

@Composable
private fun DetailStat(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            java.text.NumberFormat.getIntegerInstance().format(value),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================================
// 탭 / 본문
// ============================================================================

@Composable
internal fun DetailTabs(selected: Int, showEpisodeTab: Boolean, onSelect: (Int) -> Unit) {
    val labels = buildList {
        add("정보")
        add("스토리")
        add("관련 작품")
        if (showEpisodeTab) add("회차")
    }
    TabRow(
        selectedTabIndex = selected.coerceIn(0, labels.lastIndex),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = selected == index,
                onClick = { onSelect(index) },
                text = { Text(label, maxLines = 1) }
            )
        }
    }
}

/** 긴 줄거리는 6줄까지만 보여주고 "더보기"로 펼친다. */
@Composable
internal fun ExpandableText(text: String, modifier: Modifier = Modifier, collapsedLines: Int = 6) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.86f),
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow }
        )
        if (overflow || expanded) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "접기" else "더보기")
            }
        }
    }
}

@Composable
internal fun DetailInfoContent(anime: Anime) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val rows = listOf(
            "방영" to anime.airedDate,
            "형식" to anime.format,
            "연도" to anime.year,
            "제작" to anime.studios.joinToString(", "),
            "원어" to anime.native,
            "영어" to anime.english,
            "로마자" to anime.romaji,
            "동의어" to anime.synonyms,
            "출처" to anime.source,
            "진행" to anime.note
        )
        val visible = rows.filter { it.second.isNotBlank() }
        visible.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    label,
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    value,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.86f)
                )
            }
        }
        if (visible.isEmpty() && anime.genres.isEmpty()) {
            Text(
                "표시할 정보가 없어요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// 관련 작품
// ============================================================================

@Composable
internal fun RelatedEntryRow(
    title: String,
    subtitle: String,
    meta: String,
    poster: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .lilacClickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimeImage(
            model = poster,
            contentDescription = title,
            modifier = Modifier.size(64.dp, 88.dp).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun RelatedPosterCard(anime: Anime, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(124.dp)
            .clip(RoundedCornerShape(14.dp))
            .lilacClickable(onClick = onClick)
    ) {
        AnimeImage(
            model = anime.poster.ifBlank { anime.backdrop },
            contentDescription = anime.title,
            modifier = Modifier.fillMaxWidth().height(172.dp).clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(6.dp))
        Text(anime.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(anime.year.takeIf { it.isNotBlank() }, anime.format.takeIf { it.isNotBlank() })
            .joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// 회차 목록
// ============================================================================

@Composable
internal fun EpisodeListHeader(total: Int, newestFirst: Boolean, onSortChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "에피소드",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$total",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = newestFirst,
            onClick = { onSortChange(true) },
            label = { Text("최신순") }
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = !newestFirst,
            onClick = { onSortChange(false) },
            label = { Text("오래된순") }
        )
    }
}

@Composable
internal fun EpisodePager(page: Int, pageCount: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(enabled = page > 0, onClick = onPrev) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "이전 회차 페이지")
        }
        Text(
            "${page + 1} / $pageCount",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(enabled = page < pageCount - 1, onClick = onNext) {
            Icon(Icons.Default.ChevronRight, contentDescription = "다음 회차 페이지")
        }
    }
}

/**
 * 회차 한 줄. 썸네일이 있으면(Re:Anime) 썸네일을, 없으면 회차 번호 뱃지를 보여준다.
 * 마지막으로 본 회차는 테두리로 표시하고, 다운로드된 회차는 연한 보라색 배경으로 구분한다.
 */
@Composable
internal fun EpisodeRow(
    ep: Episode,
    isReAnime: Boolean,
    isDownloaded: Boolean,
    downloadingProgress: Float?,
    watchProgress: Float?,
    isLastPlayed: Boolean,
    isOffline: Boolean,
    canDownload: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onCancelDownload: () -> Unit,
    onDownload: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val colors = MaterialTheme.colorScheme
    val watched = (watchProgress ?: 0f) >= 0.92f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .background(if (isDownloaded) colors.primaryContainer.copy(alpha = 0.45f) else colors.surfaceContainerLow)
            .then(if (isLastPlayed) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
            .lilacClickable(onClick = onPlay)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (ep.thumbnailUrl.isNotBlank()) {
            Box(Modifier.size(width = 104.dp, height = 60.dp).clip(RoundedCornerShape(10.dp))) {
                AnimeImage(
                    model = ep.thumbnailUrl,
                    contentDescription = ep.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Text(
                    ep.displayNumber,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    ep.displayNumber,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onPrimaryContainer,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ep.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (watched) colors.onSurfaceVariant else colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (watched) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "시청 완료",
                        tint = colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (isReAnime && ep.nativeTitle.isNotBlank()) {
                Text(
                    ep.nativeTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isReAnime && (ep.isFiller || ep.isRecap || ep.airedDate.isNotBlank())) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (ep.isFiller) EpisodeTag("FILLER", colors.error)
                    if (ep.isRecap) EpisodeTag("RECAP", colors.tertiary)
                    if (ep.airedDate.isNotBlank()) {
                        Text(
                            ep.airedDate.substringBefore("T"),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
            when {
                !ep.playable -> Text(
                    "재생 불가",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.error
                )
                isDownloaded -> Text(
                    "오프라인 시청 가능",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary
                )
                downloadingProgress != null -> Text(
                    "다운로드 중 ${(downloadingProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary
                )
            }
            if (watchProgress != null && watchProgress > 0f && !watched) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { watchProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = colors.primary
                )
            }
        }

        when {
            isDownloaded -> IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "삭제", tint = colors.error)
            }
            downloadingProgress != null -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancelDownload)
            ) {
                CircularProgressIndicator(
                    progress = { downloadingProgress },
                    modifier = Modifier.size(32.dp),
                    color = colors.primary,
                    strokeWidth = 3.dp
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "다운로드 취소",
                    tint = colors.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
            !isOffline -> IconButton(enabled = canDownload, onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = "다운로드", tint = colors.primary)
            }
            else -> Icon(
                Icons.Default.CloudOff,
                contentDescription = "오프라인",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun EpisodeTag(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

package com.lilac.anime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lilac.anime.data.*

@Composable
fun SearchScreen(
    vm: AnimeViewModel,
    open: (Anime) -> Unit,
    onNavigate: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val searchList = remember(vm.homeAnime, vm.allAnime) {
        (vm.allAnime + vm.homeAnime).distinctBy { it.id }
    }

    AppScaffold(selected = "search", onSelect = onNavigate) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            Text("검색", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("작품명, 장르 검색", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurface) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = Lilac,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(18.dp),
                singleLine = true
            )

            Spacer(Modifier.height(20.dp))

            val results = searchList.filter {
                query.isBlank() || it.title.contains(query, true) || it.genres.any { genre -> genre.contains(query, true) }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results) { anime ->
                    Row(
                        Modifier.fillMaxWidth().clickableNoIndication { open(anime) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = anime.poster,
                            contentDescription = anime.title,
                            modifier = Modifier.size(width = 78.dp, height = 110.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(anime.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                anime.genres.joinToString(" · "),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.lilac.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lilac.anime.data.*

@Composable
fun HeroCard(anime: Anime, open: (Anime) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(300.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickableNoIndication { open(anime) }
    ) {
        AsyncImage(
            model = anime.backdrop,
            contentDescription = anime.title,
            // height(200.dp)를 fillMaxSize()로 변경하여 부모 Box(300.dp)를 꽉 채우도록 수정했습니다.
            modifier = Modifier.fillMaxSize(), 
            contentScale = ContentScale.Crop // 여백 없이 꽉 채우고 싶다면 Crop 유지
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)))
        Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
            Text("FEATURED", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(anime.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { open(anime) },
                colors = ButtonDefaults.buttonColors(containerColor = Lilac, contentColor = Color.White)
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("지금 보기", color = Color.White)
            }
        }
    }
}

package com.lilac.anime

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lilac.anime.data.*

@Composable
fun ThemeOption(title: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickableNoIndication { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Lilac.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                null,
                tint = if (selected) Lilac else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(14.dp))
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

package com.lilac.anime

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.lilac.anime.data.*

fun Long?.orZero(): Long = this ?: 0L

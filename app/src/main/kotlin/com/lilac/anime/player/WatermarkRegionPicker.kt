package com.lilac.anime.player

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A watermark area, normalized to the video frame (0.0 .. 1.0). */
data class WatermarkRect(val x: Float, val y: Float, val w: Float, val h: Float)

object WatermarkRegions {
    const val MAX_REGIONS = 4

    fun parse(raw: String): List<WatermarkRect> = raw.split(';').mapNotNull { part ->
        val v = part.split(',').mapNotNull { it.trim().toFloatOrNull() }
        if (v.size != 4) return@mapNotNull null
        val x = v[0].coerceIn(0f, 1f)
        val y = v[1].coerceIn(0f, 1f)
        val w = v[2].coerceAtMost(1f - x)
        val h = v[3].coerceAtMost(1f - y)
        if (w <= 0.002f || h <= 0.002f) null else WatermarkRect(x, y, w, h)
    }.take(MAX_REGIONS)

    fun serialize(list: List<WatermarkRect>): String = list.joinToString(";") {
        String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", it.x, it.y, it.w, it.h)
    }
}

/**
 * Grabs the current video frame (without subtitles) so the user can pick a region
 * on it. The watermark filter must already be suspended by the caller, otherwise
 * the frame would show the already-removed result.
 */
suspend fun captureVideoFrame(@Suppress("UNUSED_PARAMETER") context: Context, engine: MpvPlayerEngine): ImageBitmap =
    engine.captureVideoBitmap().asImageBitmap()

@Composable
fun WatermarkRegionPickerDialog(
    frame: ImageBitmap,
    initial: List<WatermarkRect>,
    onConfirm: (List<WatermarkRect>) -> Unit,
    onCancel: () -> Unit
) {
    var rects by remember { mutableStateOf(initial) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text("워터마크 영역 선택", color = Color.White, fontSize = 16.sp)
                Text(
                    "지울 워터마크 위를 드래그해서 영역을 지정하세요. 최대 ${WatermarkRegions.MAX_REGIONS}개까지 지정할 수 있어요.",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )
                BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val aspect = frame.width.toFloat() / frame.height.toFloat()
                    val boxAspect = maxWidth / maxHeight
                    val imageWidth = if (aspect > boxAspect) maxWidth else maxHeight * aspect
                    val imageHeight = if (aspect > boxAspect) maxWidth / aspect else maxHeight

                    Canvas(
                        modifier = Modifier
                            .size(imageWidth, imageHeight)
                            .background(Color.DarkGray)
                            .pointerInput(frame) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        dragStart = offset
                                        dragCurrent = offset
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        dragCurrent = change.position
                                    },
                                    onDragEnd = {
                                        val a = dragStart
                                        val b = dragCurrent
                                        dragStart = null
                                        dragCurrent = null
                                        val width = size.width.toFloat()
                                        val height = size.height.toFloat()
                                        if (a != null && b != null && width > 0f && height > 0f) {
                                            val x1 = (minOf(a.x, b.x) / width).coerceIn(0f, 1f)
                                            val y1 = (minOf(a.y, b.y) / height).coerceIn(0f, 1f)
                                            val x2 = (maxOf(a.x, b.x) / width).coerceIn(0f, 1f)
                                            val y2 = (maxOf(a.y, b.y) / height).coerceIn(0f, 1f)
                                            if (abs(x2 - x1) > 0.01f && abs(y2 - y1) > 0.01f) {
                                                val added = rects + WatermarkRect(x1, y1, x2 - x1, y2 - y1)
                                                rects = added.takeLast(WatermarkRegions.MAX_REGIONS)
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        dragStart = null
                                        dragCurrent = null
                                    }
                                )
                            }
                    ) {
                        drawImage(
                            image = frame,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                        )
                        rects.forEach { r ->
                            val topLeft = Offset(r.x * size.width, r.y * size.height)
                            val rectSize = Size(r.w * size.width, r.h * size.height)
                            drawRect(Color(0x33FF4060), topLeft, rectSize)
                            drawRect(Color(0xFFFF4060), topLeft, rectSize, style = Stroke(width = 2.dp.toPx()))
                        }
                        val a = dragStart
                        val b = dragCurrent
                        if (a != null && b != null) {
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
                                size = Size(abs(a.x - b.x), abs(a.y - b.y)),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onCancel) { Text("취소") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { rects = rects.dropLast(1) },
                        enabled = rects.isNotEmpty()
                    ) { Text("마지막 삭제") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onConfirm(rects) },
                        enabled = rects.isNotEmpty()
                    ) { Text("적용") }
                }
            }
        }
    }
}

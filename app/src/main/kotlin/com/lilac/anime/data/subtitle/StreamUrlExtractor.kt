package com.lilac.anime

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lilac.anime.data.*
import kotlinx.coroutines.flow.map

@Composable
fun StreamUrlExtractor(
    targetUrl: String,
    onQualitiesFound: (List<StreamQuality>) -> Unit,
    onSubtitleFound: (String) -> Unit
) {
    val detectedUrls = remember { mutableListOf<String>() }
    var isSubtitleFound by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?, 
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                        if (!isSubtitleFound && url.contains(".vtt")) {
                            isSubtitleFound = true
                            Handler(Looper.getMainLooper()).post {
                                onSubtitleFound(url)
                            }
                        }

                        if (url.contains(".m3u8") && !url.contains("ad")) {
                            if (!detectedUrls.contains(url)) {
                                detectedUrls.add(url)
                                
                                Handler(Looper.getMainLooper()).post {
                                    // 경로 문자열로 화질 1차 유추
                                    val qualityList = detectedUrls.map { u ->
                                        val label = if (u.contains("/sd/")) "720p" else "1080p"
                                        StreamQuality(label, u)
                                    }
                                    
                                    // 1080p나 720p 라벨이 여러 개일 경우 겹치지 않게 #1, #2 넘버링 처리
                                    val finalQualityList = qualityList.groupBy { it.label }.flatMap { (lbl, streams) ->
                                        if (streams.size > 1) {
                                            streams.mapIndexed { idx, sq -> StreamQuality("$lbl #${idx + 1}", sq.url) }
                                        } else {
                                            streams
                                        }
                                    }
                                    
                                    onQualitiesFound(finalQualityList)
                                }
                            }
                        }
                        
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                loadUrl(targetUrl)
            }
        },
        modifier = Modifier.size(0.dp)
    )
}

package com.lilac.anime.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

data class DesktopAniSkipSegment(val type:String,val startTime:Double,val endTime:Double)
object AniSkipDesktopService {
 private val client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();private val allowed=setOf("op","ed","mixed-op","mixed-ed")
 suspend fun getSkipTimes(title:String,episode:Int,length:Int)=withContext(Dispatchers.IO){
  val id=findMalId(title)?:return@withContext emptyList<DesktopAniSkipSegment>();val q="types=op&types=ed&types=mixed-op&types=mixed-ed"+(if(length>0)"&episodeLength=$length"else"")
  val req=HttpRequest.newBuilder().uri(URI("https://api.aniskip.com/v2/skip-times/$id/$episode?$q")).header("Accept","application/json").header("User-Agent","LilacAnime Desktop").GET().build()
  val r=runCatching{client.send(req,HttpResponse.BodyHandlers.ofString())}.getOrNull()?:return@withContext emptyList<DesktopAniSkipSegment>();if(r.statusCode() !in 200..299)return@withContext emptyList<DesktopAniSkipSegment>()
  Regex("\\\"skipType\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"[\\s\\S]*?\\\"startTime\\\"\\s*:\\s*([0-9.]+)[\\s\\S]*?\\\"endTime\\\"\\s*:\\s*([0-9.]+)").findAll(r.body()).mapNotNull{m->val t=m.groupValues[1];val s=m.groupValues[2].toDoubleOrNull();val e=m.groupValues[3].toDoubleOrNull();if(t in allowed&&s!=null&&e!=null&&e>s)DesktopAniSkipSegment(t,s,e)else null}.distinctBy{"${it.type}:${it.startTime}:${it.endTime}"}.sortedBy{it.startTime}.toList()
 }
 private fun findMalId(title:String):Int?{val q=URLEncoder.encode(title,StandardCharsets.UTF_8);val req=HttpRequest.newBuilder().uri(URI("https://api.jikan.moe/v4/anime?q=$q&limit=10")).header("Accept","application/json").GET().build();val r=runCatching{client.send(req,HttpResponse.BodyHandlers.ofString())}.getOrNull()?:return null;return if(r.statusCode() in 200..299)Regex("\\\"mal_id\\\"\\s*:\\s*(\\d+)").find(r.body())?.groupValues?.get(1)?.toIntOrNull()else null}
}

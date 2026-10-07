package com.lilac.anime.network

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Re:ANIME -> FlixCloud resolver built directly from the current FlixCloud HAR flow.
 *
 * Flow copied from the captured player:
 *   Re:ANIME /api/flix -> FlixCloud /e/... -> embedded data -> /api/m3u8/{token}
 *   -> WASM _s/_r -> PBKDF2/SHA-256/AES-CBC -> fetch8 master URL
 *   and the same WASM instance's _c() -> 32-byte manifest XOR key (PK).
 *
 * This intentionally does not depend on the FlixCloud player UI, window.__pk,
 * performance entries, or simulated server-button clicks.
 */
object ReAnimeFlixCloud {
    private const val TAG = "ReAnimeFlixCloud"
    private const val REANIME = "https://reanime.to"

    data class Result(
        val m3u8Url: String,
        val pk: String,
        val referer: String,
        val headers: String
    )

    fun prepareUrl(raw: String): String = runCatching {
        val uri = Uri.parse(raw)
        val b = uri.buildUpon()
        fun add(name: String, value: String) {
            if (uri.getQueryParameter(name) == null) b.appendQueryParameter(name, value)
        }
        add("autoPlay", "true")
        add("skI", "false")
        add("skO", "false")
        add("start_at", "0")
        add("project_r_ts", System.currentTimeMillis().toString())
        b.build().toString()
    }.getOrDefault(raw)

    /** Resolve the Re:ANIME API server exactly as the HAR does. */
    fun resolveFlixUrl(anilistId: Int, episode: Int, userAgent: String): String? {
        val url = "$REANIME/api/flix/$anilistId/$episode"
        return runCatching {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .header("Referer", "$REANIME/")
                .build()
            okhttp3.OkHttpClient().newCall(req).execute().use { response ->
                val root = JSONObject(response.body?.string().orEmpty())
                val servers = root.optJSONArray("servers") ?: return@use null
                val candidates = buildList {
                    for (i in 0 until servers.length()) {
                        val o = servers.optJSONObject(i) ?: continue
                        val link = o.optString("dataLink").trim()
                        if (link.startsWith("https://flixcloud.cc/e/", true)) {
                            add(o.optString("serverName") to link)
                        }
                    }
                }
                candidates.firstOrNull { it.first.equals("HD-2", true) }?.second
                    ?: candidates.firstOrNull { it.first.contains("HD-2", true) }?.second
                    ?: candidates.firstOrNull { it.first.equals("HD-1", true) }?.second
                    ?: candidates.firstOrNull()?.second
            }
        }.onFailure { Log.e(TAG, "API_FAILED $url", it) }.getOrNull()
    }

    /** Inject the HAR bootstrap into an already configured WebView. */
    fun injectBootstrap(view: WebView, flixUrl: String, onResult: (Result?, String?) -> Unit) {
        val prepared = prepareUrl(flixUrl)
        val startedAt = System.currentTimeMillis()
        var attempts = 0
        var completed = false
        val handler = Handler(Looper.getMainLooper())

        fun finish(result: Result?, error: String?) {
            if (completed) return
            completed = true
            handler.removeCallbacksAndMessages(null)
            if (result != null) {
                Log.d(TAG, "BOOTSTRAP_OK m3u8=${result.m3u8Url} pkLength=${result.pk.length}")
            } else {
                Log.e(TAG, "BOOTSTRAP_FAILED error=${error ?: "unknown"}")
            }
            onResult(result, error)
        }

        fun poll() {
            if (completed) return
            attempts++
            view.evaluateJavascript("window.__lilacReAnimeFlixResult || ''") { raw ->
                val decoded = raw.orEmpty().trim().trim('"')
                    .replace("\\\"", "\"")
                    .replace("\\/", "/")
                    .replace("\\\\", "\\")
                if (decoded.isNotBlank()) {
                    val parsed = runCatching {
                        val o = JSONObject(decoded)
                        if (!o.optBoolean("ok")) return@runCatching null to o.optString("error")
                        val m3u8 = o.optString("m3u8")
                        val pk = o.optString("pk")
                        if (m3u8.isBlank() || pk.isBlank()) null to "bootstrap returned incomplete result"
                        else Result(
                            m3u8,
                            pk,
                            prepared,
                            "User-Agent: ${view.settings.userAgentString}\nReferer: https://flixcloud.cc/\nOrigin: https://flixcloud.cc"
                        ) to null
                    }.getOrElse { null to it.message }
                    if (parsed.first != null) finish(parsed.first, null)
                    else finish(null, parsed.second ?: "bootstrap failed")
                    return@evaluateJavascript
                }
                if (attempts >= 300 || System.currentTimeMillis() - startedAt > 60_000) {
                    finish(null, "bootstrap timeout attempts=$attempts")
                } else {
                    handler.postDelayed(::poll, 200)
                }
            }
        }

        Log.d(TAG, "BOOTSTRAP_INJECT url=$prepared")
        view.evaluateJavascript(SCRIPT, null)
        handler.postDelayed(::poll, 100)
    }

    /** Load the FlixCloud page and run the exact crypto bootstrap in the page itself. */
    fun bootstrap(view: WebView, flixUrl: String, onResult: (Result?, String?) -> Unit) {
        val prepared = prepareUrl(flixUrl)
        var injected = false
        view.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                Log.d(TAG, "PAGE_FINISHED url=$url")
                if (!injected && url?.contains("flixcloud.cc", true) == true) {
                    injected = true
                    injectBootstrap(v ?: view, prepared, onResult)
                }
            }
        }
        view.loadUrl(prepared, mapOf("Referer" to "$REANIME/"))
    }

    /** JS copied from the HAR's current FlixCloud crypto path, with no player UI dependency. */
    private val SCRIPT = """
(function(){
  if(window.__lilacReAnimeFlixStarted)return;
  window.__lilacReAnimeFlixStarted=true;
  window.__lilacReAnimeFlixResult='';
  const fail=(e)=>{window.__lilacReAnimeFlixResult=JSON.stringify({ok:false,error:String(e&&e.message||e)});console.error('[LilacFlix] '+String(e&&e.stack||e));};
  const esc=s=>String(s).replace(/[.*+?^${'$'}()|[\]\\]/g,'\\${'$'}&');
  const get=(html,key)=>{const m=html.match(new RegExp('(?:["\\']?)'+esc(key)+'(?:["\\']?)\\s*:\\s*["\\']([^"\\']*)["\\']'));return m?m[1]:null};
  const b64=s=>{const x=atob(String(s).replace(/-/g,'+').replace(/_/g,'/'));const a=new Uint8Array(x.length);for(let i=0;i<x.length;i++)a[i]=x.charCodeAt(i);return a};
  const b64s=a=>{let s='';for(let i=0;i<a.length;i+=0x8000)s+=String.fromCharCode.apply(null,a.subarray(i,Math.min(i+0x8000,a.length)));return btoa(s)};
  const sha=async s=>{const d=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(s));return Array.from(new Uint8Array(d)).map(x=>x.toString(16).padStart(2,'0')).join('')};
  (async()=>{
    try{
      const html=document.documentElement.innerHTML;
      const seed=get(html,'obfuscation_seed');
      const payload=get(html,'w_payload');
      if(!seed||!payload)throw new Error('embedded data missing seed='+!!seed+' payload='+!!payload);
      let e=seed;for(let i=0;i<3;i++)e=await sha(e+i);let a=e;for(let i=0;i<3;i++)a=await sha(a+i);
      const names={videoField:'vf_'+e.substring(0,8),keyField:'kf_'+e.substring(8,16),ivField:'ivf_'+e.substring(16,24),containerName:'cd_'+e.substring(24,32),arrayName:'ad_'+e.substring(32,40),objectName:'od_'+e.substring(40,48),tokenField:e.substring(48,64)+'_'+e.substring(56,64),keyFrag2Field:a.substring(0,16)+'_'+a.substring(16,24)};
      const frag=get(html,names.keyField),iv=get(html,names.ivField),key2=get(html,names.keyFrag2Field),token=get(html,names.tokenField);
      if(!frag||!iv||!key2||!token)throw new Error('crypto fields missing frag='+!!frag+' iv='+!!iv+' key2='+!!key2+' token='+!!token);
      const api=await fetch('/api/m3u8/'+encodeURIComponent(token),{credentials:'same-origin'});if(!api.ok)throw new Error('token HTTP '+api.status);const w=await api.json();
      const vf=await sha(token+'vid');const kf=await sha(token+'key');const E=w[vf.substring(0,10)],Y=w[kf.substring(0,10)];if(!E||!Y)throw new Error('token fields missing');
      const wasm=b64(payload), inst=(await WebAssembly.instantiate(wasm,{})).instance, ex=inst.exports, mem=ex.memory;if(mem.buffer.byteLength===0)mem.grow(1);const bytes=new Uint8Array(mem.buffer);
      const t=b64(frag), k=b64(key2), y=b64(Y), n=t.length, p1=1000,p2=p1+n,p3=p2+n,p4=p3+n;bytes.set(t,p1);bytes.set(k,p2);bytes.set(y,p3);ex._s(parseInt(seed.substring(0,8),16));ex._r(p1,p2,p3,p4,n);
      const H=new Uint8Array(ex.memory.buffer).slice(p4,p4+n);
      const pkPtr=ex._c(), pkBytes=new Uint8Array(ex.memory.buffer).slice(pkPtr,pkPtr+32), pk=b64s(pkBytes);
      const base=await crypto.subtle.importKey('raw',H,{name:'PBKDF2'},false,['deriveBits']);const bits=await crypto.subtle.deriveBits({name:'PBKDF2',salt:new TextEncoder().encode(seed),iterations:1000,hash:'SHA-256'},base,256);const J=new Uint8Array(bits);for(let i=0;i<32;i++)J[i]^=seed.charCodeAt(i%seed.length);
      const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',J));const aes=await crypto.subtle.importKey('raw',digest,{name:'AES-CBC'},false,['decrypt']);const plain=await crypto.subtle.decrypt({name:'AES-CBC',iv:b64(iv)},aes,b64(E));const m3u8=new TextDecoder().decode(plain).trim();if(!m3u8)throw new Error('empty HLS URL');
      window.__lilacReAnimeFlixResult=JSON.stringify({ok:true,m3u8:m3u8,pk:pk});
      console.log('[LilacFlix] BOOTSTRAP_OK '+m3u8);
    }catch(e){fail(e)}
  })();
})();
""".trimIndent()
}

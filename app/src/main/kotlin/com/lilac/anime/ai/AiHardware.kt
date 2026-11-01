package com.lilac.anime.ai

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import com.google.ai.edge.litert.NpuAcceleratorProvider
import com.google.ai.edge.litert.NpuCompatibilityChecker
import java.io.File

/**
 * 칩셋 벤더. libToken은 LiteRT dispatch/compiler-plugin 라이브러리 파일명 매칭용이다
 * (대소문자·밑줄 제거 후 비교: libLiteRtDispatch_GoogleTensor.so → "googletensor").
 */
enum class SocVendor(val libToken: String, val label: String) {
    QUALCOMM("qualcomm", "Qualcomm Snapdragon"),
    MEDIATEK("mediatek", "MediaTek Dimensity"),
    SAMSUNG("samsung", "Samsung Exynos"),
    GOOGLE_TENSOR("googletensor", "Google Tensor"),
    OTHER("", "기타")
}

data class SocInfo(
    val vendor: SocVendor,
    val manufacturer: String,
    /** Build.SOC_MODEL (API 31+). 예: SM8750, MT6989. 제조사마다 표기가 달라 "힌트"로만 쓴다. */
    val model: String,
    val hardware: String
) {
    /** 소문자 영숫자만 남긴 모델 키 (모델 파일명의 SoC 토큰과 비교). */
    val modelKey: String get() = model.lowercase().filter { it.isLetterOrDigit() }
    val displayName: String get() = "${vendor.label} ${model.ifBlank { hardware }}".trim()
}

enum class NpuStatus(val message: String) {
    READY("NPU 사용 가능"),
    OS_TOO_OLD("NPU는 Android 12(API 31) 이상에서만 사용할 수 있습니다"),
    ABI_UNSUPPORTED("NPU는 arm64-v8a 기기에서만 사용할 수 있습니다"),
    UNKNOWN_VENDOR("알려진 NPU 벤더가 아닙니다 (GPU/CPU로 실행)"),
    SOC_NOT_SUPPORTED("이 칩셋은 LiteRT NPU 지원 목록에 없습니다 (GPU/CPU로 실행)"),
    RUNTIME_LIBS_MISSING("앱에 이 칩셋용 NPU 런타임 라이브러리가 포함되어 있지 않습니다")
}

object AiHardware {
    private const val TAG = "AiHardware"

    @Volatile private var cachedSoc: SocInfo? = null

    fun soc(): SocInfo = cachedSoc ?: detect().also { cachedSoc = it }

    private fun detect(): SocInfo {
        val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.orEmpty() else ""
        val model = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.orEmpty() else ""
        val hardware = Build.HARDWARE.orEmpty()
        // Build.MANUFACTURER(기기 제조사)는 쓰지 않는다: 삼성 폰도 스냅드래곤이면 칩 벤더는 Qualcomm이다.
        val probe = "$manufacturer $model $hardware ${Build.BOARD.orEmpty()}".lowercase()
        val vendor = when {
            Regex("""\b(qti|qualcomm|qcom)\b""").containsMatchIn(probe) ||
                Regex("""\b(sm|sdm|qcs|sa)\d{3,4}""").containsMatchIn(probe) -> SocVendor.QUALCOMM
            probe.contains("mediatek") || Regex("""\bmt\d{4}""").containsMatchIn(probe) ||
                Regex("""\bmtk""").containsMatchIn(probe) -> SocVendor.MEDIATEK
            manufacturer.equals("google", true) || probe.contains("tensor") ||
                Regex("""\bgs\d{3}""").containsMatchIn(probe) -> SocVendor.GOOGLE_TENSOR
            probe.contains("exynos") || probe.contains("s5e") || manufacturer.contains("samsung", true) -> SocVendor.SAMSUNG
            else -> SocVendor.OTHER
        }
        return SocInfo(vendor, manufacturer, model, hardware).also { Log.i(TAG, "SOC $it") }
    }

    /**
     * LiteRT 자체 판정이 최종 권한이다. (우리가 칩셋 목록을 하드코딩하지 않는 이유:
     * LiteRT 릴리스마다 지원 SoC가 늘어나므로 라이브러리 버전만 올리면 자동으로 따라간다.)
     */
    private fun litertSupports(vendor: SocVendor): Boolean = runCatching {
        when (vendor) {
            SocVendor.QUALCOMM -> NpuCompatibilityChecker.Qualcomm.isDeviceSupported()
            SocVendor.MEDIATEK -> NpuCompatibilityChecker.Mediatek.isDeviceSupported()
            SocVendor.GOOGLE_TENSOR -> NpuCompatibilityChecker.GoogleTensor.isDeviceSupported()
            SocVendor.SAMSUNG -> NpuCompatibilityChecker.Samsung.isDeviceSupported()
            SocVendor.OTHER -> false
        }
    }.getOrElse {
        Log.w(TAG, "NpuCompatibilityChecker failed: ${it.javaClass.simpleName}")
        false
    }

    fun npuStatus(context: Context): NpuStatus {
        if (Build.VERSION.SDK_INT < 31) return NpuStatus.OS_TOO_OLD
        if (!Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) return NpuStatus.ABI_UNSUPPORTED
        val soc = soc()
        if (soc.vendor == SocVendor.OTHER) return NpuStatus.UNKNOWN_VENDOR
        if (!litertSupports(soc.vendor)) return NpuStatus.SOC_NOT_SUPPORTED
        if (NpuLibraryDir.prepare(context, soc.vendor) == null) return NpuStatus.RUNTIME_LIBS_MISSING
        return NpuStatus.READY
    }

    /** 설정 화면/진단 로그용 한 줄 요약들. */
    fun statusLines(context: Context): List<String> {
        val soc = soc()
        return listOf(
            "칩셋: ${soc.displayName}",
            "NPU: ${npuStatus(context).message}",
            "실행 순서: ${AcceleratorPolicy.order(context).joinToString(" → ")}"
        )
    }
}

/**
 * 한 APK에 여러 벤더의 NPU 런타임(Qualcomm/MediaTek/Tensor/Samsung)을 넣으면
 * LiteRT가 nativeLibraryDir에서 "처음 발견한" libLiteRtDispatch_*.so를 로드해 버려
 * 다른 벤더 라이브러리를 잡을 수 있다 (google-ai-edge/LiteRT#11250).
 * 그래서 현재 기기 벤더의 dispatch/compiler-plugin만 심볼릭 링크한 전용 디렉터리를 만들어 넘긴다.
 *
 * 주의: nativeLibraryDir에 .so가 실제로 풀려 있어야 한다 → jniLibs.useLegacyPackaging = true.
 */
object NpuLibraryDir {
    private val cache = HashMap<SocVendor, File?>()

    @Synchronized
    fun prepare(context: Context, vendor: SocVendor): File? {
        if (vendor == SocVendor.OTHER) return null
        if (cache.containsKey(vendor)) return cache[vendor]?.takeIf { it.isDirectory }
        val result = runCatching { build(context, vendor) }
            .onFailure { Log.w("NpuLibraryDir", "build failed", it) }
            .getOrNull()
        cache[vendor] = result
        return result
    }

    private fun build(context: Context, vendor: SocVendor): File? {
        val source = File(context.applicationInfo.nativeLibraryDir)
        val target = File(context.noBackupFilesDir, "ai/npu/${vendor.name.lowercase()}")
        // 앱 업데이트 시 nativeLibraryDir 경로가 바뀌므로 매번 새로 만든다.
        target.deleteRecursively()
        target.mkdirs()
        var linked = 0
        source.listFiles()?.forEach { file ->
            val norm = file.name.lowercase().replace("_", "").replace("-", "")
            val isDispatch = norm.startsWith("liblitertdispatch")
            val isPlugin = norm.startsWith("liblitertcompilerplugin")
            if (!isDispatch && !isPlugin) return@forEach
            if (!norm.contains(vendor.libToken)) return@forEach
            Os.symlink(file.absolutePath, File(target, file.name).absolutePath)
            linked++
        }
        Log.i("NpuLibraryDir", "vendor=$vendor linked=$linked dir=${target.absolutePath}")
        return if (linked > 0) target else null
    }
}

/** CompiledModel용 Environment에 넘기는 벤더 전용 provider (BuiltinNpuAcceleratorProvider 대체). */
class VendorScopedNpuProvider(private val context: Context) : NpuAcceleratorProvider {
    override fun isDeviceSupported(): Boolean = AiHardware.npuStatus(context) == NpuStatus.READY
    override fun isLibraryReady(): Boolean = true
    override suspend fun downloadLibrary() {}
    override fun getLibraryDir(): String =
        NpuLibraryDir.prepare(context, AiHardware.soc().vendor)?.absolutePath
            ?: context.applicationInfo.nativeLibraryDir
}

package com.lilac.anime.ai.llm

import android.content.Context
import com.lilac.anime.data.subtitle.translation.localai.HuggingFaceModelFile
import com.lilac.anime.data.subtitle.translation.localai.LocalAiModelManager
import java.io.IOException

/**
 * GGUF(llama.cpp)와 .litertlm(LiteRT-LM)을 한 화면에서 다루기 위한 얇은 파사드.
 * 형식별 로직은 기존 LocalAiModelManager / LiteRtModelStore에 그대로 두고, 확장자로 분기만 한다.
 */
object LocalModelHub {
    enum class Kind(val label: String) { GGUF("GGUF"), LITERTLM("LiteRT-LM") }

    fun kindOf(fileName: String): Kind? = when {
        fileName.endsWith(".gguf", true) -> Kind.GGUF
        fileName.endsWith(".litertlm", true) -> Kind.LITERTLM
        else -> null
    }

    /** Hugging Face 저장소의 모델 파일(.litertlm + .gguf)을 API 한 번으로 조회한다. litertlm이 먼저 나온다. */
    suspend fun listFiles(repoId: String, hfToken: String?): List<HuggingFaceModelFile> =
        LiteRtModelStore.listRepoFiles(repoId, hfToken, setOf(".gguf", ".litertlm"))
            .sortedWith(compareBy({ if (kindOf(it.fileName) == Kind.LITERTLM) 0 else 1 }, { it.fileName }))

    /** 이 기기에 맞는 .litertlm 빌드 파일명(SoC 토큰 일치 NPU 빌드 → 없으면 가장 작은 범용 빌드). */
    fun recommendedFileName(context: Context, files: List<HuggingFaceModelFile>): String? =
        runCatching {
            LiteRtModelStore.pickForDevice(context, files.filter { kindOf(it.fileName) == Kind.LITERTLM })?.fileName
        }.getOrNull()

    /** 설치된 모델의 표시 이름을 돌려준다. hfToken은 .litertlm(gated 저장소)에만 적용된다. */
    suspend fun download(
        context: Context,
        file: HuggingFaceModelFile,
        hfToken: String?,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> }
    ): String = when (kindOf(file.fileName)) {
        Kind.LITERTLM -> LiteRtModelStore.download(context, file, hfToken, onProgress).displayName
        Kind.GGUF -> LocalAiModelManager.download(context, file, onProgress).displayName
        null -> throw IOException("지원하지 않는 모델 형식입니다: ${file.fileName}")
    }
}

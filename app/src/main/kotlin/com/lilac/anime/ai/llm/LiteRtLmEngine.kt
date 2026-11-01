package com.lilac.anime.ai.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.lilac.anime.ai.AiAccelerator
import com.lilac.anime.ai.AiHardware
import com.lilac.anime.ai.NpuLibraryDir
import com.lilac.anime.ai.NpuStatus
import com.lilac.anime.data.subtitle.translation.localai.LocalAiAdapterRegistry
import java.io.File
import java.io.IOException

/**
 * LiteRT-LM(.litertlm) 엔진. 같은 코드로 Qualcomm/MediaTek/Tensor/(Exynos) NPU, GPU, CPU를 모두 다룬다.
 * 칩셋 차이는 "어떤 .litertlm 파일을 쓰느냐"(SoC별 AOT 빌드)와 "어떤 dispatch 라이브러리를 로드하느냐"로만 갈린다.
 */
class LiteRtLmEngine private constructor(
    private val engine: Engine,
    override val accelerator: AiAccelerator,
    override val modelId: String,
    private val profileModel: com.lilac.anime.data.subtitle.translation.localai.LocalAiModel
) : LlmEngine {

    override val engineId: String = ENGINE_ID

    override fun generate(request: LlmRequest): String {
        val sampler = SamplerConfig(
            topK = request.sampling.topK,
            topP = request.sampling.topP.toDouble(),
            temperature = request.sampling.temperature.toDouble()
        )
        val system = Contents.of(request.systemPrompt)

        // thinking OFF를 모델이 지원하지 않으면 설정 생성/적용에서 예외가 날 수 있어 한 번 더 시도한다.
        val raw = runCatching {
            val config = if (request.thinking == ThinkingMode.AUTO) {
                ConversationConfig(
                    systemInstruction = system,
                    samplerConfig = sampler,
                    maxOutputToken = request.sampling.maxTokens
                )
            } else {
                ConversationConfig(
                    systemInstruction = system,
                    samplerConfig = sampler,
                    thinkingConfig = ThinkingConfig(enableThinking = request.thinking == ThinkingMode.ON),
                    maxOutputToken = request.sampling.maxTokens
                )
            }
            sendOnce(config, request.userPrompt)
        }.getOrElse { first ->
            Log.w(TAG, "generate with thinkingConfig failed, retry plain: ${first.message}")
            val plain = ConversationConfig(
                systemInstruction = system,
                samplerConfig = sampler,
                maxOutputToken = request.sampling.maxTokens
            )
            sendOnce(plain, request.userPrompt)
        }

        // 기존 모델별 후처리(<think> 제거 등) 재사용
        return LocalAiAdapterRegistry.resolve(profileModel).cleanOutput(raw) ?: raw
    }

    /** 자막 한 줄마다 새 Conversation을 만든다: 문맥은 프롬프트에 이미 들어 있고, KV 캐시가 누적되지 않게 하기 위함. */
    private fun sendOnce(config: ConversationConfig, user: String): String =
        engine.createConversation(config).use { conversation ->
            conversation.sendMessage(user).toString()
        }

    override fun close() {
        runCatching { engine.close() }
    }

    companion object {
        const val ENGINE_ID = "litert-lm"
        private const val TAG = "LiteRtLmEngine"

        /** 블로킹. 반드시 백그라운드 스레드에서 호출 (initialize가 수 초 걸릴 수 있음). */
        fun open(context: Context, model: LocalLlmModel, accelerator: AiAccelerator): LiteRtLmEngine {
            require(model.format == ModelFormat.LITERTLM) { "LiteRT-LM은 .litertlm 모델만 실행합니다." }
            val backend = when (accelerator) {
                AiAccelerator.NPU -> {
                    if (AiHardware.npuStatus(context) != NpuStatus.READY) {
                        throw IOException("NPU 사용 불가: ${AiHardware.npuStatus(context).message}")
                    }
                    val dir = NpuLibraryDir.prepare(context, AiHardware.soc().vendor)
                        ?: throw IOException("NPU 런타임 라이브러리 디렉터리를 만들 수 없습니다.")
                    Backend.NPU(nativeLibraryDir = dir.absolutePath)
                }
                AiAccelerator.GPU -> Backend.GPU()
                AiAccelerator.CPU -> Backend.CPU()
            }
            val cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() }
            val config = EngineConfig(
                modelPath = model.path,
                backend = backend,
                cacheDir = cacheDir.absolutePath // 2번째 로드부터 빨라짐 (NPU JIT 캐시 포함)
            )
            val engine = Engine(config)
            try {
                engine.initialize()
            } catch (t: Throwable) {
                runCatching { engine.close() }
                throw t
            }
            Log.i(TAG, "OPEN model=${model.displayName} accelerator=$accelerator")
            return LiteRtLmEngine(engine, accelerator, model.id, model.asProfileModel())
        }
    }
}

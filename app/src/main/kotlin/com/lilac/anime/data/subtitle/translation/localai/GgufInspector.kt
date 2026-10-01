package com.lilac.anime.data.subtitle.translation.localai

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException

object GgufInspector {
    private const val MAGIC = 0x46554747L

    fun inspect(file: File): GgufInspection {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val magic = raf.u32()
                if (magic != MAGIC) throw IOException("GGUF magic가 아닙니다.")
                val version = raf.u32()
                val tensorCount = raf.u64()
                val metadataCount = raf.u64()
                if (version !in 1L..3L) throw IOException("지원하지 않는 GGUF version: $version")
                if (tensorCount > 2_000_000L || metadataCount > 2_000_000L) throw IOException("잘못된 GGUF header입니다.")

                var architecture: String? = null
                var chatTemplate: String? = null
                repeat(metadataCount.toInt()) {
                    val key = raf.readString()
                    val type = raf.u32()
                    val value = raf.readValue(type)
                    when (key) {
                        "general.architecture" -> architecture = value as? String
                        "tokenizer.chat_template" -> chatTemplate = value as? String
                    }
                }

                val types = linkedSetOf<String>()
                repeat(tensorCount.toInt()) {
                    raf.readString()
                    val dims = raf.u32().toInt()
                    if (dims !in 0..16) throw IOException("잘못된 tensor dimension")
                    repeat(dims) { raf.u64() }
                    types += tensorTypeName(raf.u32().toInt())
                    raf.u64()
                }

                val quant = if (types.size == 1) types.first() else types.sorted().joinToString(",")
                GgufInspection(true, version, architecture, quant, chatTemplate, types, tensorCount, metadataCount)
            }
        }.getOrElse { GgufInspection(false, error = it.message ?: it.javaClass.simpleName) }
    }

    private fun tensorTypeName(type: Int): String = when (type) {
        0 -> "F32"; 1 -> "F16"; 2 -> "Q4_0"; 3 -> "Q4_1"; 6 -> "Q5_0"; 7 -> "Q5_1"; 8 -> "Q8_0"
        10 -> "Q2_K"; 11 -> "Q3_K"; 12 -> "Q4_K"; 13 -> "Q5_K"; 14 -> "Q6_K"; 15 -> "Q8_K"
        16 -> "IQ2_XXS"; 17 -> "IQ2_XS"; 18 -> "IQ3_XXS"; 19 -> "IQ1_S"; 20 -> "IQ4_NL"
        21 -> "IQ3_S"; 22 -> "IQ2_S"; 23 -> "IQ4_XS"; 24 -> "I8"; 25 -> "I16"; 26 -> "I32"
        27 -> "I64"; 28 -> "F64"; 29 -> "IQ1_M"; 30 -> "BF16"; 34 -> "TQ1_0"; 35 -> "TQ2_0"
        39 -> "MXFP4"; 40 -> "Q2_0C"; 41 -> "Q1_0"; 42 -> "Q2_0"
        else -> "TYPE_$type"
    }

    private fun RandomAccessFile.u32(): Long {
        var v = 0L
        repeat(4) { v = v or (readUnsignedByte().toLong() shl (it * 8)) }
        return v
    }

    private fun RandomAccessFile.u64(): Long {
        var v = 0L
        repeat(8) { v = v or (readUnsignedByte().toLong() shl (it * 8)) }
        return v
    }

    private fun RandomAccessFile.readString(): String {
        val length = u64()
        if (length < 0L || length > 16L * 1024L * 1024L) throw IOException("GGUF string too large")
        val bytes = ByteArray(length.toInt())
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun RandomAccessFile.readValue(type: Long): Any? = when (type) {
        0L, 1L, 7L -> readUnsignedByte()
        2L, 3L -> readUnsignedShort()
        4L, 5L, 6L -> u32()
        8L -> readString()
        9L -> {
            val elementType = u32()
            val count = u64()
            repeat(count.coerceAtMost(1_000_000L).toInt()) { readValue(elementType) }
            null
        }
        10L, 11L, 12L -> u64()
        else -> throw IOException("알 수 없는 GGUF metadata type=$type")
    }
}

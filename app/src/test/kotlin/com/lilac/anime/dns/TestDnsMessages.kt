package com.lilac.anime.dns

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/** 테스트용 DNS 메시지 빌더. */
internal object TestDns {

    data class Answer(val type: Int, val ttl: Long, val rdata: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    fun query(name: String, type: Int, id: Int = 0x1234): ByteArray =
        requireNotNull(DnsQueryBuilder.build(id, name, type)) { "query build failed" }

    /** IP 리터럴 rdata. (DNS 조회 없음) */
    fun addressRdata(address: String): ByteArray = InetAddress.getByName(address).address

    fun aRecord(address: String): ByteArray = addressRdata(address)

    fun aaaaRecord(address: String): ByteArray = addressRdata(address)

    fun cnameRdata(target: String): ByteArray = encodeName(target)

    fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        name.trimEnd('.').split('.').filter { it.isNotEmpty() }.forEach { label ->
            out.write(label.length)
            label.forEach { out.write(it.code) }
        }
        out.write(0)
        return out.toByteArray()
    }

    /**
     * 응답 메시지 생성.
     *
     * @param compressAnswerNames true 면 answer 이름을 question(offset 12) 으로 가리키는
     *   compression pointer 로 쓴다.
     */
    fun response(
        id: Int = 0x1234,
        questionName: String,
        questionType: Int = DnsType.A,
        rcode: Int = DnsRcode.NO_ERROR,
        answers: List<Answer> = emptyList(),
        compressAnswerNames: Boolean = false,
        truncated: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        fun u16(value: Int) {
            out.write((value ushr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        fun u32(value: Long) {
            out.write(((value ushr 24) and 0xFF).toInt())
            out.write(((value ushr 16) and 0xFF).toInt())
            out.write(((value ushr 8) and 0xFF).toInt())
            out.write((value and 0xFF).toInt())
        }

        val hasQuestion = questionName.isNotEmpty()

        u16(id)
        var flags = 0x8000 or 0x0100 or (rcode and 0x000F)
        if (truncated) flags = flags or 0x0200
        u16(flags)
        u16(if (hasQuestion) 1 else 0)
        u16(answers.size)
        u16(0)
        u16(0)

        if (hasQuestion) {
            out.write(encodeName(questionName))
            u16(questionType)
            u16(1)
        }

        answers.forEach { answer ->
            if (compressAnswerNames) {
                out.write(0xC0)
                out.write(0x0C)
            } else {
                out.write(encodeName(questionName))
            }
            u16(answer.type)
            u16(1)
            u32(answer.ttl)
            u16(answer.rdata.size)
            out.write(answer.rdata)
        }

        return out.toByteArray()
    }
}

package com.lilac.anime.dns

/** DNS RR type 값. */
object DnsType {
    const val A = 1
    const val NS = 2
    const val CNAME = 5
    const val SOA = 6
    const val PTR = 12
    const val MX = 15
    const val TXT = 16
    const val AAAA = 28
    const val SRV = 33
    const val OPT = 41
    const val SVCB = 64
    const val HTTPS = 65
}

/** DNS response code. */
object DnsRcode {
    const val NO_ERROR = 0
    const val FORMERR = 1
    const val SERVFAIL = 2
    const val NXDOMAIN = 3
    const val NOTIMP = 4
    const val REFUSED = 5

    fun name(rcode: Int): String = when (rcode) {
        NO_ERROR -> "NOERROR"
        FORMERR -> "FORMERR"
        SERVFAIL -> "SERVFAIL"
        NXDOMAIN -> "NXDOMAIN"
        NOTIMP -> "NOTIMP"
        REFUSED -> "REFUSED"
        else -> "RCODE$rcode"
    }
}

data class DnsQuestion(
    val name: String,
    val type: Int,
    val clazz: Int,
)

data class DnsAnswer(
    val name: String,
    val type: Int,
    val clazz: Int,
    val ttl: Long,
    /** 메시지 안에서 TTL 필드가 시작하는 오프셋. 캐시 TTL 감산에 사용한다. */
    val ttlOffset: Int,
    /** rdata 가 시작하는 오프셋. A/AAAA 주소 추출에 사용한다. */
    val rdataOffset: Int,
    val rdataLength: Int,
)

data class DnsMessage(
    val id: Int,
    val isResponse: Boolean,
    val opcode: Int,
    val rcode: Int,
    val truncated: Boolean,
    val questions: List<DnsQuestion>,
    val answers: List<DnsAnswer>,
    /** question 섹션이 끝나는 오프셋. TC 응답을 만들 때 사용한다. */
    val questionEndOffset: Int,
) {
    /** A/AAAA/CNAME 같은 실제 주소성 레코드가 있는지. */
    val hasAddressAnswer: Boolean
        get() = answers.any { it.type != DnsType.CNAME && it.type != DnsType.SOA && it.type != DnsType.OPT }

    /** negative caching 대상(NXDOMAIN 또는 주소 없는 NOERROR)인지. */
    val isNegative: Boolean
        get() = rcode == DnsRcode.NXDOMAIN ||
            (rcode == DnsRcode.NO_ERROR && !hasAddressAnswer)

    /** 응답에 포함된 가장 짧은 TTL. 레코드가 없으면 null. */
    val minTtl: Long?
        get() = answers.filter { it.type != DnsType.OPT }.minOfOrNull { it.ttl }
}

/**
 * bootstrap 등에서 사용할 최소 DNS 질의 생성기.
 */
object DnsQueryBuilder {
    private const val FLAG_RD = 0x0100

    fun build(id: Int, name: String, type: Int, recursionDesired: Boolean = true): ByteArray? {
        val labels = name.trimEnd('.').split('.').filter { it.isNotEmpty() }
        if (labels.isEmpty()) return null
        if (labels.any { it.length > 63 }) return null
        val nameSize = labels.sumOf { it.length + 1 } + 1
        if (nameSize > 255) return null

        val out = ByteArray(DnsMessageCodec.HEADER_SIZE + nameSize + 4)
        out[0] = ((id ushr 8) and 0xFF).toByte()
        out[1] = (id and 0xFF).toByte()
        val flags = if (recursionDesired) FLAG_RD else 0
        out[2] = ((flags ushr 8) and 0xFF).toByte()
        out[3] = (flags and 0xFF).toByte()
        out[5] = 1 // QDCOUNT = 1

        var offset = DnsMessageCodec.HEADER_SIZE
        for (label in labels) {
            out[offset++] = label.length.toByte()
            label.forEach { ch -> out[offset++] = ch.code.toByte() }
        }
        out[offset++] = 0
        out[offset++] = ((type ushr 8) and 0xFF).toByte()
        out[offset++] = (type and 0xFF).toByte()
        out[offset++] = 0 // CLASS IN
        out[offset] = 1
        return out
    }
}

/**
 * DNS 메시지 파서/패처.
 *
 * 응답을 새로 만들지 않고 앱이 보낸 질의를 그대로 upstream 에 전달한 뒤,
 * 돌아온 원본 바이트를 그대로 돌려주는 방식을 쓴다. 여기서는 캐시를 위해
 * 헤더/질문/응답 레코드의 위치와 TTL 오프셋만 해석한다.
 */
object DnsMessageCodec {
    const val HEADER_SIZE = 12

    private const val FLAG_QR = 0x8000
    private const val FLAG_OPCODE_MASK = 0x7800
    private const val FLAG_TC = 0x0200
    private const val FLAG_RCODE_MASK = 0x000F

    private const val MAX_QUESTIONS = 32
    private const val MAX_ANSWERS = 512
    private const val MAX_NAME_JUMPS = 16

    data class NameResult(val name: String, val nextOffset: Int)

    /** 파싱에 실패하면 null. 호출자는 "malformed" 로 처리하고 전달만 한다. */
    fun parse(buffer: ByteArray, length: Int): DnsMessage? {
        if (length < HEADER_SIZE || length > buffer.size) return null

        val id = u16(buffer, 0)
        val flags = u16(buffer, 2)
        val questionCount = u16(buffer, 4)
        val answerCount = u16(buffer, 6)

        if (questionCount > MAX_QUESTIONS || answerCount > MAX_ANSWERS) return null
        if (questionCount > 0 && questionCount + answerCount > 128) return null

        var offset = HEADER_SIZE
        val questions = ArrayList<DnsQuestion>(questionCount)
        repeat(questionCount) {
            val name = readName(buffer, offset, length) ?: return null
            offset = name.nextOffset
            if (offset + 4 > length) return null
            questions += DnsQuestion(
                name = name.name,
                type = u16(buffer, offset),
                clazz = u16(buffer, offset + 2),
            )
            offset += 4
        }
        val questionEndOffset = offset

        val answers = ArrayList<DnsAnswer>(answerCount)
        repeat(answerCount) {
            val name = readName(buffer, offset, length) ?: return null
            offset = name.nextOffset
            if (offset + 10 > length) return null
            val type = u16(buffer, offset)
            val clazz = u16(buffer, offset + 2)
            val ttl = u32(buffer, offset + 4)
            val rdLength = u16(buffer, offset + 8)
            val ttlOffset = offset + 4
            offset += 10
            if (offset + rdLength > length) return null
            answers += DnsAnswer(
                name = name.name,
                type = type,
                clazz = clazz,
                ttl = ttl,
                ttlOffset = ttlOffset,
                rdataOffset = offset,
                rdataLength = rdLength,
            )
            offset += rdLength
        }

        return DnsMessage(
            id = id,
            isResponse = flags and FLAG_QR != 0,
            opcode = (flags and FLAG_OPCODE_MASK) ushr 11,
            rcode = flags and FLAG_RCODE_MASK,
            truncated = flags and FLAG_TC != 0,
            questions = questions,
            answers = answers,
            questionEndOffset = questionEndOffset,
        )
    }

    /**
     * 캐시 key.
     *
     * 질문이 정확히 하나일 때만 캐시한다. OPT/ANY 같은 레코드는 제외한다.
     */
    fun cacheKey(message: DnsMessage): String? {
        if (message.questions.size != 1) return null
        val question = message.questions[0]
        if (question.name.isEmpty()) return null
        if (question.type == DnsType.OPT || question.type == 255 /* ANY */) return null
        return normalizeName(question.name) + "|" + question.type
    }

    fun normalizeName(name: String): String = name.trimEnd('.').lowercase()

    /** 같은 ID 로 캐시 응답을 되돌려줄 때 ID 를 새로 쓰고 TTL 을 경과 시간만큼 감산한다. */
    fun rewriteIdAndAgeTtls(
        buffer: ByteArray,
        length: Int,
        newId: Int,
        ttlOffsets: IntArray,
        elapsedSeconds: Long,
    ) {
        if (length >= 2) {
            buffer[0] = ((newId ushr 8) and 0xFF).toByte()
            buffer[1] = (newId and 0xFF).toByte()
        }
        if (elapsedSeconds <= 0) return
        for (offset in ttlOffsets) {
            if (offset < 0 || offset + 4 > length) continue
            val current = u32(buffer, offset)
            val aged = (current - elapsedSeconds).coerceAtLeast(1L)
            writeU32(buffer, offset, aged)
        }
    }

    /**
     * MTU 보다 큰 응답을 전달할 수 없을 때 만드는 TC(truncated) 응답.
     *
     * 헤더 + 질문만 남기고 TC 비트를 세워 클라이언트가 TCP 로 재시도하도록 한다.
     */
    fun truncate(queryOrResponse: ByteArray, length: Int, questionEndOffset: Int): ByteArray? {
        if (length < questionEndOffset || questionEndOffset < HEADER_SIZE) return null
        val out = queryOrResponse.copyOf(questionEndOffset)
        // QR=1, TC=1, RCODE=NOERROR, 나머지 카운트는 질문만 남긴다.
        val flags = u16(out, 2)
        val newFlags = (flags or FLAG_QR or FLAG_TC) and FLAG_RCODE_MASK.inv()
        out[2] = ((newFlags ushr 8) and 0xFF).toByte()
        out[3] = (newFlags and 0xFF).toByte()
        out[6] = 0
        out[7] = 0
        out[8] = 0
        out[9] = 0
        out[10] = 0
        out[11] = 0
        return out
    }

    /** 이름(label) 을 읽는다. compression pointer 를 처리하고 루프를 방어한다. */
    fun readName(buffer: ByteArray, start: Int, length: Int): NameResult? {
        if (start < 0 || start >= length) return null
        val builder = StringBuilder()
        var offset = start
        var nextOffset = -1
        var jumps = 0
        var guard = 0

        while (true) {
            if (offset < 0 || offset >= length) return null
            if (guard++ > 255) return null
            val labelLength = buffer[offset].toInt() and 0xFF

            when {
                labelLength == 0 -> {
                    offset += 1
                    if (nextOffset < 0) nextOffset = offset
                    break
                }

                labelLength and 0xC0 == 0xC0 -> {
                    if (offset + 1 >= length) return null
                    val pointer = ((labelLength and 0x3F) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
                    if (nextOffset < 0) nextOffset = offset + 2
                    if (pointer < 0 || pointer >= length) return null
                    if (++jumps > MAX_NAME_JUMPS) return null
                    offset = pointer
                }

                labelLength and 0xC0 != 0 -> return null

                else -> {
                    if (offset + 1 + labelLength > length) return null
                    if (builder.isNotEmpty()) builder.append('.')
                    builder.append(String(buffer, offset + 1, labelLength, Charsets.ISO_8859_1))
                    offset += 1 + labelLength
                }
            }
        }

        if (nextOffset < 0) return null
        return NameResult(builder.toString(), nextOffset)
    }

    fun u16(buffer: ByteArray, offset: Int): Int {
        if (offset + 2 > buffer.size) return 0
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }

    fun u32(buffer: ByteArray, offset: Int): Long {
        if (offset + 4 > buffer.size) return 0L
        return ((buffer[offset].toLong() and 0xFF) shl 24) or
            ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
            ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
            (buffer[offset + 3].toLong() and 0xFF)
    }

    private fun writeU32(buffer: ByteArray, offset: Int, value: Long) {
        if (offset + 4 > buffer.size) return
        buffer[offset] = ((value ushr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }
}

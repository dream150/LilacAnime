package com.lilac.anime.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessageCodecTest {

    @Test
    fun parsesIpv4AnswerWithCompressedName() {
        val response = TestDns.response(
            id = 0xABCD,
            questionName = "example.com",
            questionType = DnsType.A,
            answers = listOf(TestDns.Answer(DnsType.A, 300, TestDns.aRecord("93.184.216.34"))),
            compressAnswerNames = true,
        )

        val parsed = requireNotNull(DnsMessageCodec.parse(response, response.size)) { "parse failed" }

        assertEquals(0xABCD, parsed.id)
        assertTrue(parsed.isResponse)
        assertFalse(parsed.truncated)
        assertEquals(DnsRcode.NO_ERROR, parsed.rcode)

        assertEquals(1, parsed.questions.size)
        assertEquals("example.com", parsed.questions[0].name)
        assertEquals(DnsType.A, parsed.questions[0].type)

        assertEquals(1, parsed.answers.size)
        assertEquals(300L, parsed.minTtl)
        assertTrue(parsed.hasAddressAnswer)
        assertFalse(parsed.isNegative)

        val answer = parsed.answers[0]
        assertArrayEquals(
            TestDns.aRecord("93.184.216.34"),
            response.copyOfRange(answer.rdataOffset, answer.rdataOffset + answer.rdataLength),
        )
    }

    @Test
    fun parsesIpv6Answer() {
        val response = TestDns.response(
            questionName = "example.com",
            questionType = DnsType.AAAA,
            answers = listOf(TestDns.Answer(DnsType.AAAA, 120, TestDns.aaaaRecord("2001:db8::1"))),
            compressAnswerNames = true,
        )

        val parsed = DnsMessageCodec.parse(response, response.size)!!
        assertEquals(DnsType.AAAA, parsed.questions[0].type)
        assertEquals(120L, parsed.minTtl)
        assertEquals(16, parsed.answers[0].rdataLength)
    }

    @Test
    fun parsesMultipleAnswersAndUsesShortestTtl() {
        val response = TestDns.response(
            questionName = "example.com",
            answers = listOf(
                TestDns.Answer(DnsType.CNAME, 900, TestDns.cnameRdata("cdn.example.com")),
                TestDns.Answer(DnsType.A, 60, TestDns.aRecord("1.2.3.4")),
                TestDns.Answer(DnsType.A, 45, TestDns.aRecord("5.6.7.8")),
            ),
            compressAnswerNames = true,
        )

        val parsed = DnsMessageCodec.parse(response, response.size)!!
        assertEquals(3, parsed.answers.size)
        assertEquals(45L, parsed.minTtl)
        assertTrue(parsed.hasAddressAnswer)
        assertFalse(parsed.isNegative)
    }

    @Test
    fun parsesNxdomainAsNegative() {
        val response = TestDns.response(
            questionName = "not-a-real-host.example",
            rcode = DnsRcode.NXDOMAIN,
        )

        val parsed = DnsMessageCodec.parse(response, response.size)!!
        assertEquals(DnsRcode.NXDOMAIN, parsed.rcode)
        assertTrue(parsed.isNegative)
        assertFalse(parsed.hasAddressAnswer)
        assertNull(parsed.minTtl)
    }

    @Test
    fun treatsNoErrorWithoutAnswersAsNegative() {
        val response = TestDns.response(questionName = "example.com", questionType = DnsType.AAAA)
        val parsed = DnsMessageCodec.parse(response, response.size)!!
        assertEquals(DnsRcode.NO_ERROR, parsed.rcode)
        assertTrue(parsed.isNegative)
        assertFalse(parsed.hasAddressAnswer)
    }

    @Test
    fun cacheKeyIsLowerCasedAndTyped() {
        val response = TestDns.response(
            questionName = "Example.COM",
            questionType = DnsType.A,
            answers = listOf(TestDns.Answer(DnsType.A, 60, TestDns.aRecord("1.1.1.1"))),
            compressAnswerNames = true,
        )
        val parsed = DnsMessageCodec.parse(response, response.size)!!
        assertEquals("example.com|1", DnsMessageCodec.cacheKey(parsed))
    }

    @Test
    fun cacheKeyIsNullWhenQuestionCountIsNotOne() {
        val response = TestDns.response(questionName = "", answers = emptyList())
        val parsed = DnsMessageCodec.parse(response, response.size)!!
        assertNull(DnsMessageCodec.cacheKey(parsed))
    }

    @Test
    fun returnsNullForTruncatedBuffer() {
        val response = TestDns.response(
            questionName = "example.com",
            answers = listOf(TestDns.Answer(DnsType.A, 60, TestDns.aRecord("1.1.1.1"))),
            compressAnswerNames = true,
        )
        // rdata 가 잘린 버퍼
        assertNull(DnsMessageCodec.parse(response.copyOf(response.size - 3), response.size - 3))
        // 헤더보다 짧은 버퍼
        assertNull(DnsMessageCodec.parse(ByteArray(11), 11))
        // 선언된 길이보다 짧은 버퍼
        assertNull(DnsMessageCodec.parse(response, response.size - 1))
    }

    @Test
    fun readNameRejectsSelfReferencingPointerLoop() {
        val buffer = ByteArray(14)
        buffer[12] = 0xC0.toByte()
        buffer[13] = 0x0C.toByte()
        assertNull(DnsMessageCodec.readName(buffer, 12, buffer.size))
    }

    @Test
    fun readNameFollowsCompressionPointer() {
        val message = TestDns.response(
            questionName = "example.com",
            answers = listOf(TestDns.Answer(DnsType.A, 60, TestDns.aRecord("1.1.1.1"))),
            compressAnswerNames = true,
        )
        // answer 이름은 question(offset 12) 을 가리키는 pointer 다.
        val parsed = requireNotNull(DnsMessageCodec.parse(message, message.size))
        val name = requireNotNull(
            DnsMessageCodec.readName(message, parsed.questionEndOffset, message.size)
        )
        assertEquals("example.com", name.name)
        assertEquals("example.com", parsed.answers[0].name)
    }

    @Test
    fun truncateKeepsQuestionAndSetsTcFlag() {
        val response = TestDns.response(
            id = 0x7777,
            questionName = "example.com",
            answers = listOf(TestDns.Answer(DnsType.A, 60, TestDns.aRecord("1.1.1.1"))),
            compressAnswerNames = true,
        )
        val parsed = DnsMessageCodec.parse(response, response.size)!!

        val truncated = requireNotNull(
            DnsMessageCodec.truncate(response, response.size, parsed.questionEndOffset)
        )

        assertEquals(parsed.questionEndOffset, truncated.size)
        val reparsed = DnsMessageCodec.parse(truncated, truncated.size)!!
        assertTrue(reparsed.truncated)
        assertEquals(1, reparsed.questions.size)
        assertEquals(0, reparsed.answers.size)
        assertEquals(0x7777, reparsed.id)
    }

    @Test
    fun rewriteIdAndAgeTtlsPatchesIdAndReducesTtls() {
        val response = TestDns.response(
            id = 0x1234,
            questionName = "example.com",
            answers = listOf(
                TestDns.Answer(DnsType.A, 300, TestDns.aRecord("1.1.1.1")),
                TestDns.Answer(DnsType.A, 100, TestDns.aRecord("1.0.0.1")),
            ),
            compressAnswerNames = true,
        )
        val parsed = DnsMessageCodec.parse(response, response.size)!!
        val offsets = parsed.answers.map { it.ttlOffset }.toIntArray()

        val buffer = response.copyOf()
        DnsMessageCodec.rewriteIdAndAgeTtls(buffer, buffer.size, 0xBEEF, offsets, 50)

        assertEquals(0xBEEF, DnsMessageCodec.u16(buffer, 0))
        val aged = DnsMessageCodec.parse(buffer, buffer.size)!!
        assertEquals(250L, aged.answers[0].ttl)
        assertEquals(50L, aged.answers[1].ttl)
        assertEquals(50L, aged.minTtl)
    }

    @Test
    fun agingNeverDropsTtlToZero() {
        val response = TestDns.response(
            questionName = "example.com",
            answers = listOf(TestDns.Answer(DnsType.A, 10, TestDns.aRecord("1.1.1.1"))),
            compressAnswerNames = true,
        )
        val offsets = intArrayOf(DnsMessageCodec.parse(response, response.size)!!.answers[0].ttlOffset)
        val buffer = response.copyOf()
        DnsMessageCodec.rewriteIdAndAgeTtls(buffer, buffer.size, 1, offsets, 999)
        assertEquals(1L, DnsMessageCodec.parse(buffer, buffer.size)!!.answers[0].ttl)
    }

    @Test
    fun queryBuilderProducesParsableQuery() {
        val query = DnsQueryBuilder.build(0x0102, "www.example.com", DnsType.AAAA)!!
        val parsed = DnsMessageCodec.parse(query, query.size)!!
        assertFalse(parsed.isResponse)
        assertEquals(0x0102, parsed.id)
        assertEquals("www.example.com", parsed.questions[0].name)
        assertEquals(DnsType.AAAA, parsed.questions[0].type)
        assertEquals(0, parsed.answers.size)
    }

    @Test
    fun queryBuilderRejectsOverlongLabel() {
        val longLabel = "a".repeat(64)
        assertNull(DnsQueryBuilder.build(1, "$longLabel.example.com", DnsType.A))
    }
}

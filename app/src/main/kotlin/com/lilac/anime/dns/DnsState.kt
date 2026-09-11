package com.lilac.anime.dns

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DnsRunState {
    OFF,
    STARTING,
    ACTIVE,
    ERROR,
}

/**
 * 상태 사유 코드.
 *
 * hostname, 질의 내용, 서버 응답 같은 민감한 네트워크 메타데이터를 담지 않는다.
 * UI 에서 한국어 문구로 변환한다.
 */
enum class DnsStatusReason {
    NONE,
    DISABLED,
    PERMISSION_REQUIRED,
    STARTING,
    RUNNING,
    ANOTHER_VPN_ACTIVE,
    INVALID_SETTINGS,
    FOREGROUND_SERVICE_UNAVAILABLE,
    TUNNEL_ERROR,
    REVOKED,
    SERVICE_STOPPED,
}

data class DnsRuntimeStatus(
    val state: DnsRunState = DnsRunState.OFF,
    val reason: DnsStatusReason = DnsStatusReason.DISABLED,
    val profileId: String? = null,
    val profileName: String? = null,
    val mode: DnsMode = DnsMode.SYSTEM,
    val queries: Long = 0,
    val cacheHits: Long = 0,
    val failures: Long = 0,
    val cacheEntries: Int = 0,
)

/** 같은 프로세스 안에서 서비스 <-> Settings UI 가 공유하는 상태. */
object DnsRuntimeState {
    private val mutable = MutableStateFlow(DnsRuntimeStatus())

    val status: StateFlow<DnsRuntimeStatus> = mutable.asStateFlow()

    val current: DnsRuntimeStatus get() = mutable.value

    fun set(status: DnsRuntimeStatus) {
        mutable.value = status
    }

    fun update(transform: (DnsRuntimeStatus) -> DnsRuntimeStatus) {
        mutable.value = transform(mutable.value)
    }
}

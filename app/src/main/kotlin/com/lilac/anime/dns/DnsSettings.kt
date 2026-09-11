package com.lilac.anime.dns

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 영속 저장되는 DNS 설정.
 *
 * 기본값은 SYSTEM 이며, 이 경우 VPN 을 만들지 않고 Android 기본 DNS 를 그대로 쓴다.
 */
data class DnsSettings(
    val enabled: Boolean = false,
    val profileId: String = DnsProviders.SYSTEM,
    val mode: DnsMode = DnsMode.SYSTEM,
    val primary: String? = null,
    val secondary: String? = null,
    val dohUrl: String? = null,
    val bootstrap: String? = null,
    val fallbackToSystem: Boolean = true,
) {
    /** 현재 설정이 실제로 VPN DNS 를 켜는 상태인지. */
    val activeProfile: DnsProfile get() = DnsProfileResolver.resolve(this)
}

/**
 * 설정 <-> 저장소 문자열 변환.
 *
 * Android 의존성이 없어 JVM 단위 테스트에서 그대로 검증할 수 있다.
 */
object DnsSettingsCodec {
    const val KEY_ENABLED = "dns.enabled"
    const val KEY_PROFILE_ID = "dns.profileId"
    const val KEY_MODE = "dns.mode"
    const val KEY_PRIMARY = "dns.primary"
    const val KEY_SECONDARY = "dns.secondary"
    const val KEY_DOH_URL = "dns.dohUrl"
    const val KEY_BOOTSTRAP = "dns.bootstrap"
    const val KEY_FALLBACK = "dns.fallbackToSystem"

    fun decode(get: (String) -> String?): DnsSettings {
        val profileId = get(KEY_PROFILE_ID)
            ?.takeIf { DnsProviders.isBuiltIn(it) || it == DnsProviders.CUSTOM }
            ?: DnsProviders.SYSTEM

        val mode = get(KEY_MODE)
            ?.let { raw -> runCatching { DnsMode.valueOf(raw) }.getOrNull() }
            ?: if (profileId == DnsProviders.SYSTEM) DnsMode.SYSTEM else DnsMode.UDP

        val settings = DnsSettings(
            enabled = get(KEY_ENABLED)?.toBooleanStrictOrNull() ?: false,
            profileId = profileId,
            mode = mode,
            primary = get(KEY_PRIMARY)?.trim()?.takeIf { it.isNotEmpty() },
            secondary = get(KEY_SECONDARY)?.trim()?.takeIf { it.isNotEmpty() },
            dohUrl = get(KEY_DOH_URL)?.trim()?.takeIf { it.isNotEmpty() },
            bootstrap = get(KEY_BOOTSTRAP)?.trim()?.takeIf { it.isNotEmpty() },
            fallbackToSystem = get(KEY_FALLBACK)?.toBooleanStrictOrNull() ?: true,
        )

        // SYSTEM 은 항상 VPN 을 쓰지 않는다.
        if (settings.profileId == DnsProviders.SYSTEM && settings.mode == DnsMode.SYSTEM) {
            return settings.copy(enabled = false)
        }

        // 저장된 값이 깨져 있으면 VPN 을 켜지 않는다. (앱은 계속 정상 동작)
        if (settings.enabled && DnsValidation.validate(settings).isNotEmpty()) {
            return settings.copy(enabled = false)
        }
        return settings
    }

    /** null 값은 "해당 key 삭제"를 의미한다. */
    fun encode(settings: DnsSettings): Map<String, String?> = mapOf(
        KEY_ENABLED to if (settings.enabled) "true" else "false",
        KEY_PROFILE_ID to settings.profileId,
        KEY_MODE to settings.mode.name,
        KEY_PRIMARY to settings.primary,
        KEY_SECONDARY to settings.secondary,
        KEY_DOH_URL to settings.dohUrl,
        KEY_BOOTSTRAP to settings.bootstrap,
        KEY_FALLBACK to if (settings.fallbackToSystem) "true" else "false",
    )
}

val Context.dnsDataStore: DataStore<Preferences> by preferencesDataStore(name = "dns_settings")

/** DNS 설정을 DataStore 에 저장/복원한다. */
class DnsSettingsRepository(private val context: Context) {

    val flow: Flow<DnsSettings> = context.dnsDataStore.data.map { prefs ->
        DnsSettingsCodec.decode { key -> prefs[stringPreferencesKey(key)] }
    }

    suspend fun current(): DnsSettings = flow.first()

    suspend fun save(settings: DnsSettings) {
        context.dnsDataStore.edit { prefs ->
            DnsSettingsCodec.encode(settings).forEach { (key, value) ->
                if (value == null) {
                    prefs.remove(stringPreferencesKey(key))
                } else {
                    prefs[stringPreferencesKey(key)] = value
                }
            }
        }
    }
}

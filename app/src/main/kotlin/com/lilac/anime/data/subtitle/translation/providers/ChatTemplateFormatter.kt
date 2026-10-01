package com.lilac.anime.data.subtitle.translation.providers

object ChatTemplateFormatter {
    private const val BOS = "<｜hy_begin▁of▁sentence｜>"
    private const val USER = "<｜hy_User｜>"
    private const val ASSISTANT = "<｜hy_Assistant｜>"
    private const val PLACEHOLDER_8 = "<｜hy_place▁holder▁no▁8｜>"
    private const val PLACEHOLDER_3 = "<｜hy_place▁holder▁no▁3｜>"

    fun name(template: String?): String = when {
        template.isNullOrBlank() -> "none"
        template.contains(USER) && template.contains(ASSISTANT) -> "hunyuan-dense"
        else -> "unknown"
    }

    fun formatForGeneration(template: String?, userContent: String): String {
        if (template.isNullOrBlank()) return userContent
        if (template.contains(USER) && template.contains(ASSISTANT)) {
            return BOS + USER + userContent + PLACEHOLDER_8
        }
        return userContent
    }

    fun formatWithSystemForGeneration(template: String?, systemContent: String, userContent: String): String {
        if (template.isNullOrBlank()) return userContent
        if (template.contains(USER) && template.contains(ASSISTANT)) {
            return BOS + systemContent + PLACEHOLDER_3 + USER + userContent + PLACEHOLDER_8
        }
        return userContent
    }
}

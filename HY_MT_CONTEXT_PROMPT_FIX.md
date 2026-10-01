# HY-MT1.5 contextual prompt fix

- The subtitle file translator and realtime translator already send one current cue per model invocation. The previous cues are supplied separately as `contextLines`; they are not concatenated into the source cue.
- The old saved default prompt had no `{context}` placeholder. It was therefore wrapped in an English `[CONTEXT - DO NOT TRANSLATE]` block, which did not match Tencent's documented HY-MT contextual-translation prompt.
- HY-MT1.5 now uses the official contextual prompt form for its default/legacy-default prompt:
  `{context}\n参考上面的信息，把下面的文本翻译成韩语，注意不需要翻译上文，也不要额外解释：\n{source_text}`
- When there is no preceding context, the prompt falls back to the official segment-translation form instead of emitting an empty context section.
- Existing saved default prompts are recognized and migrated at inference time. Custom user prompts are not overwritten.
- `SubtitleFormatTranslator` and `RealtimeSubtitleTranslator` retain their existing one-cue-per-request flow. ASS Dialogue parsing/rendering and subtitle tags are not changed.
- Build verification could not be run from this archive because it does not include a Gradle wrapper (`gradlew`).

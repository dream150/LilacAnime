# LilacAnime 0.2.5

This is a clean source tree based directly on the supplied `LilacAnime-main.zip`.

- No `SubtitleSourceResolver.kt` was added.
- No `isKoreanOrEnglishSubtitle()` exists.
- No `SUBTITLE_LANGUAGE_CHECK_FAILED` exists.
- Kairan does not perform a subtitle-language test.
- The title normalization regexes were replaced with character-based normalization to avoid Android ICU regex compatibility problems.
- Build marker: `KairanSubtitleService-0.2.5-CLEAN`.
- Project root is `LilacAnime-main/`; do not extract it into another project as a nested source tree.

After extracting, open the `LilacAnime-main` directory itself as the Android Studio/Neonide project.

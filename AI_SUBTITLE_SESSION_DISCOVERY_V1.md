# AI session + subtitle discovery V1

## Local AI
- Home starts a background model preload using the persistent session owner `__home__`.
- Detail screen switches the persistent session owner to the current anime ID.
- Episodes of the same anime reuse the loaded llama/native session.
- LocalTranslator/RealtimeSubtitleTranslator cannot accidentally release a persistent anime session.
- Switching to another anime releases the previous native session and loads the new one.

## Subtitle discovery
Before playback is started, PlayerScreen discovers:
- Kairan
- Csora
- Jimaku
- Re:Anime subtitle tracks when the hidden extractor reports them
- Linkkf subtitle URL when the resolver reports one
- previously cached subtitle assets

Only discovered/usable candidates are shown. The user chooses a subtitle and then chooses original view or translated view.

## Title resolution
Kairan/Csora search now uses `SubtitleTitleResolver`:
1. existing Korean title if available
2. NamuWiki resolver
3. optional TMDB fallback when `pref_tmdb_api_key` is configured
4. original title fallback

NamuWiki results are cached by the existing resolver. TMDB results are cached separately.

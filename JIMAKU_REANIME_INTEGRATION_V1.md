# Jimaku + Re:ANIME AniList ID integration

Applied to the 0.3.7 Re:ANIME detail/episodes project.

## Flow

Re:ANIME AniList ID
-> Jimaku homepage `data-extra.anilist_id`
-> `/entry/{id}`
-> subtitle file links
-> episode-aware ASS/SSA/SRT/VTT selection
-> local cache
-> `SubtitleStore` source `jimaku`
-> Player subtitle source menu

## Selection

- AniList ID is the primary match key.
- No NamuWiki/title translation is used for Jimaku lookup.
- ASS/SSA is preferred over SRT/VTT.
- Exact `SxxEyy` / episode matches are preferred.
- `furigana` / `.ja` ASS files receive a small quality tie-breaker.
- Episode ranges such as `01-02` are accepted when no exact file exists.
- Downloaded files are cached under the AniList ID and registered in `SubtitleStore`.

## Player

A `Jimaku` subtitle source was added to the player subtitle source selector and background subtitle search.

## Validation

The supplied Jimaku HAR was parsed to verify the AniList ID 178789 -> entry 12216 mapping and episode file selection.

The full Android Gradle build was not run in this environment because the project wrapper requires Gradle 9.0.0 and that distribution is not cached locally.

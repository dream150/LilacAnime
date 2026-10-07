# Re:ANIME HAR UI/UX v9

This patch rebuilds the Android Re:ANIME search/detail experience around the supplied
`reanime detail.har` contract.

## Re:ANIME request flow

- Search/catalog: `GET https://reanime.to/api/v1/search`
- Search facets: `GET https://reanime.to/api/v1/search?facets=true&limit=0`
- Detail/episode payload: `GET https://reanime.to/anime/{slug}/__data.json`
- Watch payload fallback: `GET https://reanime.to/watch/{slug}/__data.json`
- Playback server list: `GET https://reanime.to/api/flix/{anilistId}/{episode}`
- Episode thumbnails: `GET https://reanime.to/api/thumbnails/{anilistId}`
- Download status: `GET https://reanime.to/api/v1/downloads/check?...`

## Android changes

Added:
- `app/src/main/kotlin/com/lilac/anime/data/ReAnimeHarClient.kt`
- `app/src/main/kotlin/com/lilac/anime/data/ReAnimeHarParser.kt`
- `app/src/main/kotlin/com/lilac/anime/ui/reanime/ReAnimeUi.kt`

Changed:
- `AnimeRepository.kt`: Re:ANIME detail/episode loading now consumes `__data.json`.
- `AppNavigation.kt`: Re:ANIME uses the new dedicated search/detail UI.

The LinkKF and AnimeNosub paths are left on their existing implementations.

## UI

Search:
- Re:ANIME-branded search screen
- poster grid
- loading/empty states
- direct `/api/v1/search` requests

Detail:
- poster hero
- play-first-episode action
- library action
- episode/info/related tabs
- episode metadata from HAR
- filler/recap/playable indicators
- relation navigation using the exact Re:ANIME slug

Playback continues through the existing Re:ANIME FlixCloud HAR resolver.

# LinkKF linkani.tv rebuild

Source of truth: `linkani.tv.har` supplied with this task.

The LinkKF path was rebuilt around the current site observed in the HAR:
- Catalog: `https://linkani.tv/list/2/` with `/page/N/`
- Detail: `https://linkani.tv/ani/{id}/`
- Episodes: `/watch/{id}/a1/kN/`
- Playback: `var player_aaaa={...}` embedded in the watch HTML
- Video: `actual_url`/`url` -> `aniplayer1.site/.../index.m3u8`
- Subtitle: `subtitle_url` -> `aniplayer1.site/.../sub.vtt`
- The captured media requests did not require a Referer/Origin header, so the resolver does not invent one.

The old Linkkf JSON/API/WebView playback path is no longer used for LinkKF playback.
ReAnime, Animenosub, translation, offline engine, and other source implementations were not intentionally refactored.

LinkKF-specific UI integrations retained:
- Home schedule tabs
- LinkKF sections
- Search/filter drawer
- Detail episode list/server UI
- Existing PlayerScreen controls/subtitle/auto-next lifecycle

The player now resolves a fresh watch page for each episode, which is important because the HLS URL contains short-lived `md5`/`expires` query parameters.

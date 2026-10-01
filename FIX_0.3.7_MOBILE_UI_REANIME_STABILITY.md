# 0.3.7 mobile UI / Re:ANIME stability patch

Applied to the uploaded LilacAnime-0.3.7 project.

- Mobile player keeps the screen awake while PlayerScreen is active.
- Search tag filters are opened through a mobile `태그 필터` button.
- Tag filters are displayed in a right-side drawer with reset/apply actions.
- Re:ANIME playback uses a playback generation guard to invalidate late WebView callbacks.
- Previous/next episode changes stop the old mpv playback and invalidate stale callbacks.
- Re:ANIME quality/stream changes invalidate the previous playback generation.
- Re:ANIME `streamUrl` changes are loaded by one LaunchedEffect path; the quality selector no longer calls `engine.load()` directly.
- The patch also guards delayed Re:ANIME playback completion work against stale generations.

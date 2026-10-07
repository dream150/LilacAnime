# Re:Anime desktop playback lifecycle port — v19

This revision ports the important Re:Anime playback lifecycle from the LilacAnime desktop implementation into the Android player without changing LinkKF or AnimeNoSub playback logic.

## Changes

- Re:Anime `/api/flix/{anilistId}/{episode}` now collects all FlixCloud `dataLink` candidates.
- Candidate order is HD-2, HD-1, then remaining servers, matching the desktop fallback strategy.
- Each candidate gets an independent 12-second bootstrap deadline; a dead/stalled candidate no longer blocks the next episode indefinitely.
- Each candidate resets detected M3U8/PK/session state before loading, preventing one episode/server from leaking state into another.
- Stream/token data is not persisted as a long-lived playback cache. A fresh FlixCloud page/session is used for each playback.
- Existing local FlixCloud HLS proxy and libmpv/libass playback path are retained.
- libmpv suppresses delayed END_FILE events during episode replacement until the replacement emits START_FILE, preventing stale EOF events from triggering an extra automatic episode switch.
- LinkKF and AnimeNoSub code paths are intentionally unchanged.

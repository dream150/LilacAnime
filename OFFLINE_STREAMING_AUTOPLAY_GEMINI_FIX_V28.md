# v28 — foreground libmpv re-entry + Gemini cloud API

- Offline re-entry is now extended to streaming and episode auto-next/manual episode switching.
- PlayerScreen acquires a fresh foreground MpvPlayerEngine when switching episodes.
- The mpv Surface and libass overlay are keyed by engine so they cannot remain attached to the previous engine.
- Gemini follows the desktop model-discovery flow: list available generateContent models first, select a usable Flash model, then use structured JSON and simpler JSON fallbacks.
- Gemini model-list HTTP failures are no longer swallowed; the actual server status/message reaches the settings test.
- Default stale Gemini model text was changed from gemini-3.5-flash-lite to gemini-2.5-flash.

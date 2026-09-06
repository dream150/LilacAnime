# Offline / OP-ED / autoplay fix

- Offline completion detection now treats the mpv-native offline files/status as the source of truth and recovers from stale metadata/status races.
- OP/ED analysis no longer depends on `streamUrl`; it is triggered by the completed local MP4 + mpv READY state, which matches the current native player architecture.
- OP/ED analysis waits for the local mpv duration and then runs `detectSkipSegmentsOffline`.
- Existing autoplay logic is preserved; episode switching continues to use the current mpv load/replace path.

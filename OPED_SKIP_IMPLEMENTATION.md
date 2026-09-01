# OP/ED Skip implementation

LilacAnime no longer calls the AniSkip API for OP/ED skip detection.

The player now uses:

1. AnimeThemes API to resolve the anime from MAL and verify OP/ED themes.
2. AnimeThemes resources to obtain the AniDB relation.
3. Open Anime Timestamps `timestamps.json` for episode-specific opening/ending positions.
4. Entries whose source is `anime_skip` are deliberately ignored.

The player keeps the existing auto-skip/manual-skip UI and settings.

## Important limitation
AnimeThemes provides theme metadata/media, not timestamps inside a full episode. Open Anime Timestamps therefore supplies the episode positions. Its database is not complete and was last updated in 2022, so episodes without a non-AniSkip timestamp entry will simply have no skip button/auto-skip data.

For the opening end, the implementation uses a 90-second theme length cap (or the ending start when that is earlier). For the ending, it uses the preview start when available, otherwise the episode end.

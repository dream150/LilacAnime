# Re:Anime TMDB Korean-title / subtitle season preservation

- Added `Anime.season`, `Anime.seasonYear`, and `Anime.seasonNumber` as separate metadata.
- Re:Anime search/API and HAR detail parsers preserve season fields instead of only putting them in `note`.
- TMDB Korean title replacement changes only `Anime.title`; season metadata remains intact.
- SubtitleTitleResolver appends `시즌 N` only when a real season number is known/inferred from title. Airing seasons such as WINTER/SPRING/SUMMER/FALL are not incorrectly treated as Season 2/3.
- Jimaku continues to use the Re:Anime AniList ID, so title translation does not replace its primary identity.

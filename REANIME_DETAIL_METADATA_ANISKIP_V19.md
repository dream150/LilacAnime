# Re:Anime detail metadata + AniSkip v19

- Re:Anime detail page now uses the SSR `anime:{...}` object for description, titles, IDs, format/status/source/season/year, dates, genres, studios and relations.
- Re:Anime relation entries are stored in `Anime.reAnimeRelated` and shown in the Related Works tab.
- Re:Anime detail opens on the Episodes tab by default.
- Re:Anime episodes are displayed on the default tab; the other tabs remain available.
- AniSkip requests now use repeated `types[]` parameters and include `recap`, with duration-matched lookup followed by episodeLength=0 fallback.

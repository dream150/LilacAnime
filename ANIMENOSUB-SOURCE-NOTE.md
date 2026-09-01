# Animenosub source integration

- Settings > 콘텐츠 / 영상 소스 switches the entire catalog source between Linkkf and Animenosub.
- Linkkf parsing and IDs remain unchanged.
- Animenosub uses its own `animenosub:` anime IDs and its own cached catalog.
- Home/search/all/detail/episode loading follow the selected source.
- Existing Linkkf cached catalog is preserved when switching to Animenosub and back.
- Animenosub episode pages are stored directly as `Episode.videoUrl`; the existing stream extractor is used for playback/download.
- Existing subtitle providers remain independent of the video catalog source.

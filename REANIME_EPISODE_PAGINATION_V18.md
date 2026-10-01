Re:ANIME episode pagination v18

- Reads limit/offset/total/totalPages from the embedded episodes payload.
- Fetches remaining pages sequentially using the detail route.
- Accepts a page only when the returned offset matches the expected offset.
- Tries page, episode_page, ep_page, and offset+limit query forms.
- Deduplicates by episode number.
- Re:ANIME detail UI displays 100 episodes per page to match the provider payload.

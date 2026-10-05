# Subtitle Selection Profile V2

- 자막 선택창 목록을 LazyColumn으로 바꾸고 원문/번역 선택을 목록 아래 고정.
- 작품별 선택 프로필을 저장하고 회차마다 현재 사용 가능한 자막의 새 URL/파일에 매칭.
- 선택한 자막이 다음 회차에 없으면 해당 회차에서만 선택창을 다시 표시.
- Jimaku/Re:Anime는 같은 소스 안에서 여러 트랙을 동시에 선택 가능.
- Kairan/Csora/Linkkf는 한 회차의 활성 자막을 하나 선택.
- 오프라인 저장 시 Jimaku의 해당 회차 후보를 모두 다운로드하고 Re:Anime/Kairan/Csora 자막도 오프라인 SubtitleStore에 등록.
- Kairan/Csora 검색 제목은 SubtitleTitleResolver를 통해 한국어 제목을 우선 사용.

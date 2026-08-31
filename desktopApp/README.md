# LilacAnime Desktop

Desktop stream extraction uses Playwright/Chromium instead of Android WebView.

The Android implementation observes real WebView network requests via `shouldInterceptRequest()`.
This Desktop implementation does the equivalent with Playwright `BrowserContext.onRequest()`.

## Browser

A Chromium/Chrome executable is required. The app checks:

1. `LILAC_CHROMIUM_PATH`
2. `CHROME_PATH`
3. common Chrome/Chromium installation paths

Example:

```bash
export LILAC_CHROMIUM_PATH=/path/to/chrome
./gradlew :desktopApp:run
```

If no system browser is available, install the Playwright Chromium browser for the selected Playwright version.

## mpv

`mpv` must be available on PATH.


# URL Cookie Scanner — Spring Boot + MongoDB + Playwright (Chromium)

This service scans a URL with **headless Chromium** (via Microsoft Playwright for Java),
follows redirects, and collects cookies — including those created by client-side JavaScript.

## APIs
- `POST /api/scan`
  ```json
  { "url": "https://example.com" }
  ```
  → `{"transactionId":"<id>"}`
- `GET /api/status/{transactionId}`
  →
  ```json
  {
    "transactionId": "...",
    "url": "https://...",
    "status": "PENDING|RUNNING|COMPLETED|FAILED",
    "redirectChain": [
      { "url": "...", "statusCode": 301, "setCookieHeaders": ["..."] },
      { "url": "...", "statusCode": 200, "setCookieHeaders": [] }
    ],
    "cookies": [
      { "name": "SESSIONID", "url": "https://...", "cookieDomain": ".example.com", "cookieExpire": "2025-12-31T00:00:00Z" }
    ]
  }
  ```

## What’s captured
- Redirect chain for the main frame (each hop + `Set-Cookie` headers).
- Cookies set via response headers **and** cookies created by JS on the page (by diffing cookie snapshots).
- For each cookie we return: `name`, `url` (page where it appeared), `cookieDomain`, `cookieExpire` (`null` means session cookie).

## Run locally
1. **Requirements**: Java 21, Maven, MongoDB running at `mongodb://localhost:27017/url_cookie_scanner`.
2. First run will trigger a Chromium download by Playwright.
   - Optionally set `PLAYWRIGHT_BROWSERS_PATH=0` (default) or a shared cache path.
3. Start:
   ```bash
   mvn spring-boot:run
   ```

## Notes
- URL validation ensures http/https scheme, punycode host, and normalizes path.
- For increased safety (avoiding SSRF to private networks), add a DNS/IP allowlist or block RFC1918 ranges.
- `scanner.maxRedirects`, `scanner.navTimeoutSeconds`, `scanner.userAgent` are configurable in `application.properties`.

## Caveats
- Mapping JS-set cookies to the exact page is best-effort (attributed to the final navigated URL).
- Some sites set cookies on subresources; those are still captured by the browser context and may be attributed to the final page.

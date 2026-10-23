# Web search upgrade

The search stack now lives in `com.karen.search` and is called through the
existing `com.karen.ui.WebSearch` facade. Chat, Workspace, the one-time consent
dialog, the Privacy toggle, and the phone-tool `web_search` path keep their
current UI and behavior.

## What changed

- **Providers are swappable.** `SearchRouter` tries providers in a fixed order:
  1. Exa (only when the user already has an Exa key saved in Settings → Privacy)
  2. SearXNG (only when a user-supplied instance URL is configured)
  3. DuckDuckGo Instant Answer
  4. Wikipedia search API

  The first provider with results wins, matching the old Exa → DuckDuckGo →
  Wikipedia behavior. Provider errors are collected, not thrown, so one outage
  does not cancel the turn.

- **Page fetching and extraction.** For the top results (default: two pages),
  `WebPageFetcher` requests the public `http(s)` URL, checks `robots.txt`, rejects
  private/link-local hosts and non-HTML content, and extracts readable text with
  `HtmlContentExtractor`. The extractor is deliberately tolerant of malformed
  markup and strips scripts, styles, navigation, forms, and comments.

- **Deterministic query expansion.** `QueryExpander` strips an explicit
  `/search` prefix, removes question/stop words for a keyword query, and adds a
  `latest` variant only for freshness queries. No extra model call is made.

- **Deterministic ranking.** `SearchRanker` normalizes URLs, removes tracking
  parameters, merges duplicate results, scores title/body term overlap, provider
  priority, recency (for fresh queries), and provider order, then returns a
  stable list.

- **Local cache.** `SearchCache` stores provider results and extracted pages in
  SQLite (`karen_search_cache_v1.db`). Keys are SHA-256 hashes of the normalized
  query/URL, never the raw question. Cache entries are per-process-safe and
  trimmed at most once per hour.

- **Bounded agent.** `SearchAgent` runs one primary query, optionally one
  follow-up query when snippets are insufficient, fetches a bounded number of
  pages, and stops at an overall deadline. Every network call has its own
  timeout and every failure degrades to snippet-only evidence.

- **Prompt-injection handling.** Extracted text is treated as untrusted data.
  `EvidenceVerifier` flags instruction-like page content, and the prompt block
  labels it, tells the model to ignore commands inside it, and uses delimited
  source entries. Page content is never executed or used as a tool instruction.

## Cache lifetimes

| Entry | Fresh for | Kept for offline fallback |
| --- | --- | --- |
| Search results | 15 minutes | 7 days |
| Fetched pages | 24 hours | 7 days |

Expired entries are only served after a live request fails. A successful live
request always replaces the cache entry.

## Limits

- 2 queries per turn (primary + at most one follow-up)
- 5 results per query
- 8 ranked results
- 2 pages fetched normally, up to 1 additional page when evidence is thin
- 12,000 characters per extracted page
- 6,000 characters of evidence in the model prompt (configurable through
  `SearchOptions.maxContextChars`)
- 20 seconds overall, 8 seconds per query and per page

These are defaults in `SearchOptions`; callers can pass a smaller or larger
budget without touching the UI.

## Configuration

### Exa

Unchanged: save an Exa key in Settings → Privacy. When present it is tried
first. Keys are read from `UserPrefs` and are never logged.

### SearXNG

There is intentionally no new settings screen and no bundled instance. A
SearXNG base URL can be stored in the same on-device preferences:

```kotlin
UserPrefs.setSearxngUrl(context, "https://searx.example.org")
```

The provider appends `/search` (unless the URL already ends in `/search`) and
requests `format=json`. Many public instances disable the JSON API; when that
happens the router moves on to DuckDuckGo. A self-hosted instance on
localhost/LAN is allowed because the URL was explicitly configured by the user.
Clearing the value returns to the original keyless path:

```kotlin
UserPrefs.setSearxngUrl(context, "")
```

No SearXNG server, API key, or credential is hardcoded.

## Privacy

- Only the query string needed for the active provider leaves the device.
- Page fetches send the URL to that site, with a Karen user agent.
- The cache stores hashed keys and result/page content locally; it is not
  uploaded.
- The existing `webSearchEnabled` toggle and one-time consent dialog still gate
  every search path.
- Exa keys stay in `SharedPreferences`; they are never included in cache keys,
  errors, or logs.

## Failure behavior

| Situation | Result |
| --- | --- |
| Provider timeout / HTTP error | Try the next provider; record a short error |
| All providers fail | Serve stale cached results if within 7 days, else no web context |
| Page timeout / non-HTML / private host | Keep the search snippet as evidence |
| `robots.txt` disallows page | Keep the search snippet; do not fetch |
| Malformed HTML | Tolerant extraction; may fall back to snippet |
| Query returns nothing | Report insufficient evidence; model is told to say what is missing |
| Coroutine cancelled | Cancellation propagates; blocking connections are disconnected |

## Tests

Focused JVM tests live in `app/src/test/java/com/karen/search/` and cover:

- URL normalization, redirect unwrapping, and private-host blocking
- deterministic query expansion
- tolerant HTML extraction and entity decoding
- provider JSON parsing
- deduplication and ranking
- `robots.txt` matching
- cache TTL, stale fallback, and trim scheduling
- prompt-injection detection and evidence sufficiency

They are pure-JVM tests; `org.json` is supplied as a test dependency because the
Android `android.jar` stubs do not implement it. Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline
```

If the JUnit/org.json artifacts are not in the local Gradle cache, run the same
task online once; the production build remains offline-capable because these are
`testImplementation` dependencies only.

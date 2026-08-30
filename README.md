# Pokemon Card and Slab Arb

Native Android app (Kotlin + Jetpack Compose) that watches eBay for
top-tier-character Pokemon card arbitrage opportunities (Pikachu, Charizard,
Espeon, Gengar, Lugia, Mewtwo, Umbreon — see `Matching.TOP_TIER_CHARACTERS`).
Three independent, start/stop-able bots, each with its own tab and (for the
two arb bots) its own accumulating result history — **only one runs at a
time** (see "Single-bot lock" below):

1. **Ending-soon auctions** — worldwide PSA10/CGC10 graded-slab auctions
   (any seller) ending within 5 hours, compared against the cheapest
   *current* AU buy-it-now listing for the same card. Only auctions landing
   under $150 AUD (bid + shipping); only rows at ≥30% estimated profit
   against that reference price. Rows are confirmed against eBay's own
   structured item data (`conditionDescriptors` on the item-detail
   endpoint), not just the listing title, before showing.
2. **PCG vs PSA/CGC** — the cheapest active PCG-10 Japanese-language listing
   per card (PCG is a smaller/cheaper grader — a PCG-10 often undersells an
   equivalent PSA-10), priced against the cheapest *current* AU PSA-10
   buy-it-now listing (a current CGC-10 listing shown alongside as bonus
   context, not part of the profit gate). Under $150 AUD landed, ≥30%
   profit only.
3. **Raw grading (Japan)** — a Kotlin/OpenCV port of the Python
   `store_grader_japan.py` tool: raw (ungraded) top-tier-character listings
   located in Japan, under $20 AUD landed (searched up to 3,000 listings
   deep per character, same as the Python original), each CV-graded
   on-device (same grading engine as `grading_engine.py`, via
   `GradingPipeline`). Listings with more than 1 item available are skipped
   (the photo may not be the actual card — checked via the Browse API's
   `estimatedAvailableQuantity`). **This bot does no profit-arb comparison**
   — only a predicted straight 10 makes the final list. Scanning is
   batched: 50 not-yet-scanned listings graded per cycle, one cycle every
   15 minutes, up to 900 total — no listing is ever graded twice, and
   qualifying results accumulate across batches rather than being replaced.

Bots 1 and 2 auto-check **at most once a day** while running — "Run now" is
the only way to trigger an out-of-cycle scan. This keeps API usage and
rate-limit risk predictable on a shared developer key rather than
configurable per-bot polling intervals. Bot 3 runs on its own 15-minute
batch cadence instead (see above). Bots 1 and 2's results **accumulate**
across cycles rather than being replaced each time — an auction stays in
the list until it actually ends, a PCG listing stays until it no longer
appears in a fresh search. History is in-memory only and clears when the
app process restarts (this includes bot 3's "already scanned" tracking —
a fresh app run can re-scan listings from a previous run).

This is a personal tool, not a published product — it's meant to be run by
people who supply their own eBay developer credentials (see below).

> **Built for the Australian eBay market, but meant to be forked.** If
> you're outside AU, don't expect this to work out of the box — the
> reference-price comparisons and the raw-card sourcing country are all
> AU-specific by default. It's deliberately structured so those pieces are
> easy to swap out; see **"Adapting this for a different region"** below
> for exactly what to change. PRs that generalize this further (e.g. a
> region picker instead of hardcoded constants) are welcome.

## Single-bot lock

All three bots hit the same eBay developer key, so **only one may run at a
time, no exceptions.** Trying to start a second bot while one is already
running always shows a confirmation dialog ("Another bot is running — stop
it and start this one instead?") rather than silently queueing, running
concurrently, or refusing outright.

## Why current buy-it-now price, not sold price

An earlier version of this app compared against eBay's *last actual sold*
price, scraped from eBay's login-walled sold-listings search (the Browse
API has no sold-price endpoint on a standard developer key —
`findCompletedItems` is retired, and its replacement, the Marketplace
Insights API, is gated to major partners only). That scraper proved too
fragile and rate-limit-prone in practice (repeated HTTP 403s from eBay's
bot-detection during ordinary use), so it's been removed entirely — along
with the in-app "Sign in to eBay" session-cookie login it depended on. This
app no longer touches your eBay login at all outside the optional watchlist
consent flow described below.

All three bots now compare exclusively against **current, active
buy-it-now listings** — fetched directly through the reliable Browse API,
no session or scraping required. Before picking a reference price, matched
current listings have extreme-outlier prices excluded
(`PriceUtils.excludeExtremePrices` — anything more than 3x above or below
the median of the matched set is dropped) so a single mispriced or
troll listing can't distort the comparison. The cheapest surviving listing
is then used as the reference (both its price and its real URL, so you can
click straight through to it).

## Watchlist auto-seed (optional)

All three bots can automatically add their qualifying results to your
**real eBay watchlist** — a Kotlin port of `store_grader_japan.py`'s
`watch_items()` (Trading API `AddToWatchList`). This is a separate,
optional concern from everything else in the app:

- It's gated entirely on whether you've completed a separate OAuth login
  (Settings → "Connect watchlist") — the app never touches your watchlist
  unless this is explicitly connected. With no eBay API creds/auth
  populated it's a silent no-op, same as the Python original.
- This is a **user-authorization-code** OAuth flow (`EbayUserAuth.kt`),
  distinct from the client-credentials app token used for search — it
  authorizes acting on your real account, so it needs its own consent
  screen and its own eBay **RuName** (developer.ebay.com → User Tokens →
  create one pointing at `http://localhost:8080/oauth/callback`). The app
  runs a tiny loopback HTTP server on-device (127.0.0.1:8080) to catch the
  redirect — the same RFC 8252 pattern the desktop tool's `--auth` flow
  already uses, just running on the phone instead of a PC. If you've
  already set up an `EBAY_RUNAME` for the desktop tools, the same RuName
  works here unchanged.
- Auction and PCG rows are watched once they've already cleared the profit
  gate. Raw-grading rows are watched only at grade ≥10 (mirrors the
  Python tool's `WATCH_MIN_GRADE`) — which is every row currently shown,
  since the raw bot's own filter already requires a clean, unflagged 10.
- Tokens (access + refresh) are stored encrypted on-device
  (`Settings.watchlistAccessToken` / `watchlistRefreshToken`), refreshed
  automatically, and can be disconnected independently of your eBay dev
  keys via "Disconnect watchlist" in Settings.

## Architecture

```
app/src/main/java/com/psa10arb/app/
├── MainActivity.kt          — Compose UI: tabs, settings dialog, result cards
├── WatchlistLoginScreen.kt  — in-app WebView eBay consent (user OAuth, for watchlist)
├── ScanViewModel.kt         — app state, the three start/stop/run-now loops,
│                              the single-bot lock, and history accumulation
├── Psa10ArbApplication.kt   — OpenCV + logger + cache-cleaner init
├── data/
│   ├── Settings.kt          — encrypted on-device storage (dev keys, thresholds, tokens)
│   ├── ApiCounter.kt        — local daily eBay-API-call counter (advisory only)
│   ├── AppLogger.kt         — append-only on-device activity log (no secrets)
│   ├── CacheCleaner.kt      — sweeps the WebView cache dir of anything >8h old on launch
│   ├── EbayAuth.kt          — OAuth client-credentials token fetch/cache (app token)
│   ├── EbayUserAuth.kt      — OAuth authorization-code flow (user token, for watchlist)
│   ├── EbayClient.kt        — Browse API GET with retry/backoff
│   ├── PriceUtils.kt        — extreme-outlier-price exclusion shared by both arb bots
│   ├── FxRates.kt           — live AUD FX conversion for non-AUD listings (all three bots)
│   ├── WatchlistClient.kt   — Trading API AddToWatchList
│   ├── WatchlistSeeder.kt   — shared "seed the watchlist if connected" helper
│   ├── Matching.kt          — card-identity matching (TOP_TIER_CHARACTERS,
│   │                          card_key / same_card / NOT_CARD_RE merchandise filter)
│   ├── ScanRepository.kt    — bot 1 (auctions vs current AU buy-it-now)
│   ├── GradeCompareRepository.kt — bot 2 (PCG vs current PSA/CGC buy-it-now)
│   ├── RawGradingSource.kt  — bot 3 (raw Japan listings, qty-check + CV grading, no arb)
│   └── Models.kt, GradeCompareModels.kt, RawCardModels.kt
└── grading/                 — Kotlin/OpenCV-Android port of the CV grading
    engine (centering, corner whitening, edge defects, holo detection,
    print-line/warp/stain detectors, quad detection + perspective crop).
    Faithful port of a tuned Python/OpenCV tool (`grading_engine.py`), using
    the real OpenCV Android library (org.opencv:opencv) rather than a
    from-scratch reimplementation, so the numeric behavior matches closely.
```

### Matching safety rule — do not loosen

`Matching.sameCard()` requires **two or more shared significant title
tokens** between a candidate and a comparable, beyond the bare card number.
Matching on card number alone was tried in an earlier Python version of
this matching logic and produced false positives (promo numbers repeat
across unrelated characters/sets — e.g. a Charizard listing matching a
Pikachu search on a shared `#115`). Now that matching spans multiple
characters, `cardKey()` also embeds the character name ("Charizard #115"
vs "Pikachu #115") for the same reason. `Matching.NOT_CARD_RE` also filters
out stickers/coins/pins/tins/figures/etc — this app only deals in actual
trading cards. If you extend the matching logic, preserve all three
safeguards.

## Setup

1. **Android Studio** + Android SDK (compileSdk/targetSdk 34, minSdk 26).
2. **eBay developer keys**: create an app at
   [developer.ebay.com](https://developer.ebay.com), get a **production**
   client ID + client secret (client-credentials grant). Enter these in the
   app's Settings screen — they're stored encrypted on-device only, never
   committed to this repo or bundled in the APK.
3. Build: `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).
   Needs `JAVA_HOME` pointed at a JDK 17 install — a very new JDK (21/25+)
   trips up this project's Kotlin Gradle Plugin version with an
   `IllegalArgumentException` parsing the JDK version string. On Windows,
   prefer `gradlew.bat` over `./gradlew` under Git Bash/MSYS — the wrapper
   shell script has a known quoting bug with `JAVA_HOME` paths that contain
   spaces (e.g. `C:\Program Files\...`), which manifests as a confusing
   `Could not find or load main class "-Xmx64m"` error; `gradlew.bat`
   doesn't have this problem.
4. Install to a device/emulator: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
5. In the app: open Settings (gear icon), enter your eBay dev keys, then
   Start any tab. Watchlist auto-seed (RuName + "Connect watchlist") is
   optional — see above.

## Logging

Everything except secrets is logged to
`/sdcard/Android/data/com.psa10arb.app/files/psa10arb.log` on the device
(pull with `adb pull //sdcard/Android/data/com.psa10arb.app/files/psa10arb.log`
— the doubled leading slash avoids Git Bash/MSYS mangling the path into a
local one). **Never** add a log call that includes `EBAY_CLIENT_SECRET` or
either OAuth token (app or watchlist) — every existing log call site logs
queries, counts, prices, titles, URLs, HTTP status codes, and error
messages only.

## Adapting this for a different region

The reference-price comparison side of the app is currently AU-specific.
To point it at a different eBay marketplace, change these:

| What | File | Change |
|---|---|---|
| eBay marketplace context | `data/EbayClient.kt` | `X-EBAY-C-MARKETPLACE-ID` header, currently hardcoded `"EBAY_AU"`. Use eBay's marketplace ID for your target site — e.g. `EBAY_US`, `EBAY_GB`, `EBAY_DE`, `EBAY_FR`, `EBAY_CA`, `EBAY_AU`. Full list in eBay's [Browse API docs](https://developer.ebay.com/api-docs/static/rest-request-components.html#marketpl). |
| Auction search scope | `data/ScanRepository.kt` | Currently worldwide (no seller/location filter) for the buy side, with the reference-price comparison pinned to AU (`itemLocationCountry:AU` in `fetchAuAskingListings`). Adjust if your comparison market should be somewhere else. |
| PCG-10 buy location | `data/GradeCompareRepository.kt` | Query text (`"$character Japanese PCG 10"`) and `JAPANESE_RE` currently target Japanese-language PCG-10 listings specifically; the PSA/CGC reference lookup (`fetchAuGradedListings`) is pinned to AU. Adjust if your target grader/language/market differs. |
| Raw-card buy location | `data/RawGradingSource.kt` | `itemLocationCountry:$locationCountry` — currently called with `"JP"` from `ScanViewModel`. Change the call site to your target sourcing country, or thread a new setting through. |
| Auction price cap | `data/ScanRepository.kt` | `MAX_AUCTION_LANDED_AUD = 150.0` — denominated in AUD. Convert to your local currency's equivalent, or just re-pick a sensible cap. |
| PCG price cap | `data/GradeCompareRepository.kt` | `MAX_LANDED_AUD = 150.0` — same idea. |
| Raw price cap | `ScanViewModel.kt` | `MAX_RAW_LANDED_AUD = 20.0` — same idea. |
| Currency fallback strings | `ScanRepository.kt`, `GradeCompareRepository.kt`, `RawGradingSource.kt` | Several `priceObj.optString("currency", "AUD")` fallbacks — change to your local currency code. Non-matching currencies are converted via `FxRates.toAud` rather than dropped, so this is mostly a cosmetic default, not a hard filter. |
| FX conversion target | `data/FxRates.kt` | Hardcoded to AUD (`open.er-api.com/v6/latest/AUD`) — change the API URL's base currency and rename `toAud` if your target region's reference currency differs. |
| Card category | everywhere | `CATEGORY_ID = "183454"` (CCG Individual Cards). This is a global eBay category ID and should be the same across marketplaces, but verify against the [category tree](https://developer.ebay.com/api-docs/buy/browse/resources/item_summary/methods/search) for your target site if searches come back empty. |
| Character roster | `data/Matching.kt` | `TOP_TIER_CHARACTERS` — the character list every bot loops over. Add/remove names as you like; each extra character multiplies search API calls. |
| Search query language | all repositories | Character names in plain English. For non-English marketplaces (e.g. `ebay.de`), consider whether listings are commonly titled in English (usually yes, for graded/collectible cards) or need a translated/bilingual query. |
| Outlier-exclusion threshold | `data/PriceUtils.kt` | The 3x-median cutoff in `excludeExtremePrices` is a simple heuristic, not tuned against real data for any specific marketplace — adjust if a market's normal price spread is wider/narrower than AU's. |

## Known limitations

- The daily API-call counter is per-device, advisory only — the real quota
  is enforced by eBay against the developer key itself, shared across
  every device/tool using that key.
- The extreme-outlier price filter (`PriceUtils.excludeExtremePrices`) is a
  simple median-based heuristic (drop anything outside 3x the median of the
  matched set), not a statistically rigorous outlier test — with very few
  matched listings (2-3) it can behave oddly, since a median from a tiny
  sample isn't very robust either.
- The CV grading engine is a heuristic port of a tuned Python tool, not a
  guarantee — treat `probableGrade` as a filter to prioritize candidates,
  not a substitute for looking at the actual photos yourself. Crease,
  scratch, and surface (print-line/stain) detection are all disabled
  outright — each was diagnostic-logged against real eBay listing photos
  (via the raw grading bot) and found to false-positive on the large
  majority of real cards (95% for crease, 71% for surface) rather than
  actual defects, mirroring the same call the original Python tool already
  made for its own scratch detector. Corner-whitening still runs, but is
  prone to false positives from real card corners being physically
  rounded — a square pixel sample of a corner can pick up genuine
  background behind the rounded edge, which reads the same as whitening
  damage. None of these were tunable further without labeled ground-truth
  photos.
- Watchlist auto-seed's RuName-based loopback flow needs the RuName's
  configured redirect to be exactly `http://localhost:8080/oauth/callback`
  — a different port or path won't reach the on-device listener.

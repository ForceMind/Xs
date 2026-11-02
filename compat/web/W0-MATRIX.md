# W0 browser compatibility and acceptance

W0 is **not passed**. A minimal host is implemented; real X login has not been tested.
No observer, bridge, filtering, account binding or automatic archive writes are present.
Do not label this build Alpha or a complete X client.

## Automated evidence

The GitHub Actions run linked from PR #2 builds the AAR, harness and PoC APK, runs lint,
JVM/SQLite regression tests and Android API 30 and 36 emulator tests. Record final results in
[the W0 execution record](../../docs/W0-EXECUTION.md). Synthetic WebView HTML is not X.

## Manual matrix (all NOT RUN)

For **each** row record source SHA, APK SHA-256, device, Android/API, WebView package/version,
language, theme, date, steps and a sanitized pass/fail/unsupported result. Never include
credentials, cookies, raw login URLs, verification codes, DMs or private screenshots.
Target Android 16 on a physical device; API 30/36 synthetic emulator regression does not establish physical-device or live-login support.

| Area | Required separate checks | Current evidence |
| --- | --- | --- |
| Direct X login | login screen loads / credentials accepted / authenticated home works | NOT RUN, all three |
| 2FA | challenge / success / cancel / expiry | NOT RUN |
| Google | actual embedded authorization; respect provider refusal | NOT RUN; no workaround |
| Apple | authorization / redirects / return | NOT RUN; new windows unsupported in PoC |
| Passkey | provider availability / credential UI / cancel / success | NOT RUN |
| Session | relaunch / force-stop / process death / expiry / logout / account switch | NOT RUN |
| Browsing | home / detail / search / profile / deliberate back / long lists | NOT RUN |
| Social | post / reply / quote / repost / cancel / server success | NOT RUN |
| Media | images / video / fullscreen / back / background audio behavior | NOT RUN |
| Upload | image / video / multiple (max 8) / cancel / rotate / navigate while picker open | NOT RUN |
| External links | direct HTTPS / t.co / redirect / popup / blocked protocol / missing browser | NOT RUN |
| Lifecycle | rotate / background / lock / restore / renderer loss / low memory | NOT RUN live; synthetic subset in CI |
| Network | offline launch / disconnect mid-load / retry / weak connection / TLS rejection | NOT RUN live |
| Presentation | zh-CN / English / light / dark / font scale / narrow and large screens / edge-to-edge | NOT RUN |
| Backup | cloud restore / D2D transfer / OEM behavior / browser session exclusions | Rules authored; NOT RUN on devices |

## Known scope limits

- System WebView and its stock User-Agent; no impersonation, cookie copying, token input or external-session transfer.
- Only HTTPS X/legacy Twitter top-level pages remain inside. Legacy domains get no native privileges.
  CDN subresources are not globally blocked. Cross-origin iframes cannot launch native Activities.
- HTTPS external destinations require a native confirmation, then open in the browser's own session.
  Automatic external redirects are blocked. This may constrain federated login; record actual failure.
- Popups/new windows are explicitly refused. Camera/microphone/geolocation requests are denied.
  Downloads use browser confirmation; there is no authenticated download manager.
- Uploads use explicit native confirmation plus the system document picker for images/videos.
  No arbitrary filesystem access, persistent URI grant or runtime media permission. URI/MIME checks
  cannot prove provider content is safe. Selection is canceled on full navigation/destroy and rejected
  if the URL/document changed. Rotation cancels the callback; the user must select again.
- Normal WebView session persistence is allowed; Xs does not inspect or export it. Navigation state is
  saved with the platform Activity bundle, not an app-authored credentials store. FLAG_SECURE blocks
  screenshots/recent-task previews; the build has no URL/content logging or telemetry.
- No native archive entry yet: the existing archive requires a trusted identity and cannot safely be
  rebound to a scraped username. W1 explicit scopes and W2 integration remain gated.
- Android backup rules exclude archive and WebView storage, but OEM/device transfer behavior remains
  an independent verification task. SQLite is not encrypted by this work.

## Route decision

**Pending**, not failed and not passed. Install the internal CI PoC on a test device and run the
required real account flows locally. A direct-login success does not establish Google/Apple/Passkey
support. Advance to W1 only after an evidence-backed pass or an explicitly bounded conditional pass.
If required login is blocked by the provider, record it and evaluate a real-browser extension;
do not weaken security or keep substituting embedded browser engines.

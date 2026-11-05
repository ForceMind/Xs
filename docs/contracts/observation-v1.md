# Xs observation envelope v1 — DORMANT / NOT WIRED

This contract is a **local, inactive implementation and test artifact**. It does not
turn on webpage injection, automatic capture, or an Android JavaScript bridge.
Real X login and browser compatibility W0 have not been validated on a physical device.

## Message shape

The page may eventually submit a bounded observation candidate. Example:

```json
{
  "v": 1,
  "type": "POST_OPENED",
  "epoch": 7,
  "seq": 1,
  "id": "123456789",
  "postUrl": "https://x.com/example/status/123456789",
  "author": "example",
  "body": "Public post text"
}
```

Allowed keys: `v,type,epoch,seq,id,postUrl,author,body,source,visibleFraction,dwellMs`.
Unknown keys, including `scope`, `accountId`, `cookie`, `sql` or arbitrary
commands, are rejected. No native file, SQL, export, delete, fetch or preference
operation can be requested by a webpage message.

`FEED_VISIBLE` requires an allowed source and candidate visibility/dwell fields;
it is a separate permission from deliberate post opening. Candidate visibility
data is not by itself proof of user reading.

Limits: UTF-8 envelope <= 64 KiB, body <= 16,000 characters, author <= 100,
numeric nonnegative epochs, strictly increasing message sequence, 40-message
burst and 20-per-second refill in the native admission gate.

## Trust and ownership

The native application (not a web message) owns:
- Archive scope, explicit capture consent, foreground state and route.
- Verified source origin and main-frame assertion from the real WebView callback.
- Document/session epoch and monotonic timestamp.
- Evidence of deliberate navigation, rather than any DOM render/prefetch.

`ObservationRoutes` accepts only an exact HTTPS `x.com` origin for capture,
with narrowly recognized detail/feed paths. Login, DMs, composer, security,
unknown paths, subframes, untrusted origins, stale epochs and replayed sequence
numbers fail closed. The broader browser top-level navigation allowlist does
not automatically inherit archive privileges.

An allowed origin **does not authenticate the exact same-origin script**.
Even after W0 passes, observers must be reviewed and bounded, with no private
data returned to the webpage. Do not invent identity from a display handle.

## Verification

- `ObservationGateTest` runs as a dependency-free JVM suite.
- `ObservationParserTest` uses Android's actual `org.json` and verifies
  unknown-command rejection, malicious URLs, type/size checks and typed
  native-consent/gesture admission on synthetic messages.
- No `WebViewCompat.addWebMessageListener` call or JS injector is wired into W0.
- A real WebView adapter requires a separate gated design, test build and
  real-login/manual-device approval before enabling capture.

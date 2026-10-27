# Xs Android integration contract / Android 集成契约

Status: M2 design contract, **not a working X hook**. This document defines the boundary between a version-specific, authorized client adapter and the independently implemented Xs runtime.

## 1. Preconditions / 前置条件
- Verify exact host package, signing identity and version code against an explicit allowlist before enabling the adapter. Unknown versions must fail closed.
- Never request or capture passwords, cookies, access tokens, DMs, or arbitrary HTTP responses.
- Recording defaults OFF per authenticated account. Account identity must be verified by the host's authenticated session, never guessed from visible post text.
- Adapter failures must never block host navigation, posting or login.
- All storage operations run off the main thread.

## 2. Event model / 事件语义
| Event | Trigger | Required fields | Must not happen |
| --- | --- | --- | --- |
| OPEN_POST | User deliberately navigates to post detail | accountId, canonicalPostId, observedAtMs, source | Trigger on prefetch, card render, scroll |
| FEED_VISIBLE | User opted in; post is ≥60% visible in foreground for ≥1200ms | accountId, canonicalPostId, observedAtMs, feedSource | Trigger from background, prefetch, partial glimpse |
| ACCOUNT_CHANGED | Verified authenticated account changes | newAccountId | Reuse previous account's database scope |
| ACCOUNT_CLEARED | Logout/session invalidated | previousAccountId | Keep capture enabled for an unknown account |

Times are device observation times; post publication time and repost action time are independent optional fields. A repeated intentional detail open is a new visit. Feed visibility is deduplicated by (account, post, source) within the runtime's two-minute window.

## 3. Adapter interface / 适配器接口

The adapter should map version-specific UI callbacks into the existing XsCaptureBridge entry points; it must not introduce a second database or an independent X login flow. A minimal conceptual interface:

```java
interface HostObservationAdapter {
    boolean isSupportedHost(String packageName, long versionCode, String signingDigest);
    void onVerifiedAccountChanged(String accountId);
    void onPostDetailOpened(String canonicalPostId, long observedAtMs, String source);
    void onFeedVisibilityQualified(String canonicalPostId, long observedAtMs, String feedSource);
    void onAccountCleared();
}
```

This is an **interface specification**, not a claim that these methods already exist in source. Resolve actual runtime method signatures before implementing the adapter.

## 4. Acceptance tests / 验收用例
1. Unknown version/signature: all hooks disabled; original host continues to work.
2. Capture OFF: opening a post and scrolling a feed create zero events.
3. Capture ON, feed OFF: two intentional opens create two OPEN_POST events; scrolling creates none.
4. Feed ON: 59% visible or 1199ms produces zero FEED_VISIBLE events; 60% for 1200ms creates one.
5. Backgrounding during visibility countdown cancels qualification.
6. Switching accounts prevents cross-account search/history results.
7. Logout immediately stops capture; previously stored local data follows explicit retention controls.
8. Adapter exception never crashes host; no credentials or private messages appear in logs.
9. Open/search/category management remains functional offline.
10. All tests repeat after every supported host version upgrade.

## 5. Release gates / 发布门槛
- CI green for core, AAR and Android instrumentation.
- Authorized host integration tested on a documented Android version and exact host build.
- Independent tests for UI filtering and capture (one must not silently enable the other).
- Privacy, backup exclusion, export/delete and compatibility behavior verified.
- No redistribution of proprietary host APKs or third-party credentials.

# Xs client integration contract

This document defines **inputs and tests**, not a claim of an installed X patch.

## Stable entry points in Xs

- `XsCaptureBridge.bindAuthenticatedAccount(context, stableAccountId)`:
  only on verified authenticated account selection/login, **never** infer from
  the viewed post's author or a display handle.
- `XsCaptureBridge.unbindAccount()`: logout and session gaps.
- `XsCaptureBridge.onPostOpened(url, author, body, publishedAt, openedAt)`:
  exactly once after a user deliberately enters visible post details.
- `XsCaptureBridge.onFeedPostVisible(url, source, author, body,
  publishedAt, appInForeground, visibleFraction, dwellMs, seenAt)`:
  only after confirmed foreground screen time (>=60%, >=1.2s).
  Allowed sources: home_for_you, home_following, profile, search_results.
  This records a different event, never an OPEN_POST.
- `XsCaptureBridge.onPersonalAction(url, type, ...)`:
  only when original/reply/quote/repost/bookmark activity is actually verified.
  Unknown action timestamps must remain NULL.
- `XsCaptureBridge.openLocalArchive(context)`: internal Activity entry.

## Required evidence before shipping an adapter

1. Exact APKM/application version, SHA256 and origin; capture user authorization.
2. Locate post detail / feed item / account-state lifecycles using reproducible
   tests. Do not assume class names or copy guesses from a different version.
3. Verify one post-open event per user action, no extras from feed binds,
   rebinding, orientation changes, scroll previews or prefetches.
4. Prove account binding and safe logout with two different test accounts.
5. Integrate Xs runtime Android resources and Activity manifest, then test
   navigation/back stack, calls, notifications, app startup, login and XChat.
6. Only then implement optional ads/recommendations/upsell UI filtering;
   avoid payment entitlements, account security and authentication bypasses.
7. Test upgrades, signing/install warnings and safe rollback.
8. Run AAR, synthetic-app, emulator and actual X-device tests separately.

## Safe developer fallback

The `dev-harness` test APK can show the native archive screen and simulate
two independent account IDs. Its seed and simulation buttons supply synthetic
posts and call the same Xs bridge entry points. It **is not an X replacement**
or evidence that X's client hooks have been verified.

## Known limitations and next targets

- No live X adapter or installable enhanced client has been built.
- Only a bounded single user-chosen `tweets.js`/`tweet.js` file under 16 MiB
  can be imported today. The user must confirm account ownership. Large
  multi-part archives and ZIP metadata verification are future improvements.
- Current text search uses Unicode61 FTS4 Latin prefix terms and a substring
  fallback for CJK; measure performance on large on-device archives.
- UI uses 80-row incremental pages rather than loading unbounded archives.

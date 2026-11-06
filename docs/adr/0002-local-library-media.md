# ADR-0002: App-owned library, immediate open history, favorites and media sharing

Date: 2026-11-06
Status: Accepted product direction; runtime implementation pending.
Baseline reviewed: 67d2a48d01dbd08e866d0c991ef08bbaa4f09531.

## User decisions

1. Xs may use its own custom source license; the prior GPL choice is not an immutable product constraint. Final licensing requires a separate rights/terms decision, not deleting third-party notices or withdrawing historical grants.
2. Once the user enables open history, deliberately opening a post is sufficient to record it. A dwell threshold applies only to a separate optional feed-exposure feature. Confirmed repost actions also have a local record.
3. Xs has its own favorite button and categories. Favorites are app-owned local data, not server bookmarks and not owned by the currently logged-in X account.
4. Download pictures and videos, manage saved media, and share actual files through installed social applications, including WeChat, QQ, Feishu and Telegram where their supported MIME/size limits permit.

## Architecture consequences

- Maintain the Web-first product and existing Java/SQLite investment. Borrow reviewed browser lifecycle/profile/snapshot patterns from ChatGPT Studio, but do not copy account recovery/composer capture into X's public-post observer.
- Separate browser-session identity from local-library ownership. An optional actor identity annotates successful own social actions, not every historical post or favorite. Logout keeps local data.
- A native-owned navigation ID provides exactly-once OPEN_POST insertion for one deliberate detail navigation. LINK_ONLY can be recorded immediately and enriched later. Refresh, rotation, restoration, DOM rebind and prefetch do not create extra opens.
- App favorites are durable records, distinct from visit events, LOCAL_SAVE link-only actions and X server BOOKMARK state. Unfavorite, clear history and delete asset are independent operations.
- One Post may have multiple media references; actual downloaded assets have independent file lifecycle, integrity and share/export permissions. A persistent task describes download state; merely observing a media element is not downloading it.
- A single local write coordinator governs capture, import, favorites, assets and deletion, with durable scope/context checks and bounded work. Old callbacks cannot recreate deleted records.
- System sharing sends selected files using content URIs and temporary read grants. It never exposes the database/root directory, grants write access to the library or bypasses the recipient app's sending confirmation.

## Identity migration

The existing account_id column is not silently reinterpreted or flattened. Implement and test a preserving migration that retains legacy space mappings, provenance, event chronology and category memberships. User-selected consolidation is distinct from logging into another X account. The first product uses one explicit app library; browser multi-profile support must not become a dependency of basic local favorites.

## Gating and scope

This ADR supersedes conflicting ownership/trigger/media assumptions in the older Web-first plan and architecture documents. It does NOT override origin checks, sensitive-page exclusions, consent, license/rights restrictions or the requirement for actual live-site acceptance before enabling production automation.

Offline observer, favorite, download-state and share-contract development may proceed alongside real login/device checks. Sharing a link into Xs remains a companion path, not the replacement for browsing X inside Xs with automatic history.

## Evidence versus promises

Current code includes a W0 browser, native archive and explicit manual link save. This ADR does not claim automatic X history, favorites snapshot capture, in-app video downloading, outbound file sharing or a changed root license are already implemented. Verify app/device/MIME compatibility and never equate an opened Sharesheet with a delivered message.

Details: [Product](../product.md), [implementation plan](../LOCAL_LIBRARY_MEDIA_PLAN.zh-CN.md), [licensing direction](../LICENSING.md).

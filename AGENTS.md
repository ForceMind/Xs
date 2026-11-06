# AGENTS.md — Xs engineering contract

## Accepted direction and authority

Xs is an independent Web-first enhancement of the official X experience plus an app-owned local library. Do not modify/repackage the official Android APK or build a replacement social network, account service, website deployment or API proxy. Keep the public repository, independent implementation and bilingual READMEs.

Latest owner decisions (2026-11-06): [ADR-0002](docs/adr/0002-local-library-media.md), [product](docs/product.md), and [library/media specification](docs/LOCAL_LIBRARY_MEDIA_PLAN.zh-CN.md) override conflicting ownership, trigger or media assumptions in the older Web-first plan. The baseline plan/ADR-0001 and prior security/privacy constraints remain valid where not superseded.

## Product requirements that must not drift

- After explicit opt-in, a deliberate post-detail open records OPEN_POST with **no dwell threshold or extra save tap**. LINK_ONLY may be enriched later; one navigation is one event, not one event per render/message. Failed navigation, refresh, rotation, restore and prefetch are not new deliberate opens.
- Feed dwell is a different, optional feature. Foreground visibility and >=1200ms apply there only; handle long cards, overlays and background cancellation. Do not call an exposure proof of reading.
- Record successful own repost/quote/post/reply actions separately. A menu click, failure or someone else's repost is not a successful own action. Unknown times/actors remain unknown.
- Provide “Favorite in Xs” and local categories. No implicit X bookmark/like/repost request. Local favorites/history/downloads survive X logout or account switching; website identity is not their owner.
- Build image/video download, local asset management and outbound Android system sharing of actual files. Incoming share-to-Xs alone does not satisfy this requirement. Do not claim HLS manifests/segments/blob URLs are complete shareable videos.
- WeChat/QQ/Feishu/Telegram are recipient compatibility targets, not SDK/account integrations. User chooses a target/conversation and sends. No contact scraping, accessibility automation, messaging tokens or fake delivery receipts.
- Clear history, unfavorite, delete asset, clear cache, clear web session and delete library are distinct. Preserve unrelated data.

## Existing code versus future work

- src/main/java: Java 8-compatible URL, query, schema, capture policy and inactive observation admission.
- src/android/java: SQLite, archive UI, single-part posts-JS importer, manual local spaces and dormant parser/bridge foundations.
- xs-runtime: AAR. dev-harness: offline synthetic host, never a real X client.
- app-android: W0 system-WebView host, explicit manual link save and incoming Android share target. No active webpage observer or filters.
- Automatic capture, full app-native favorites, downloader/asset manager/outbound file sharing are requirements, not delivered by these docs.
- Legacy APK allowlist/metadata validation is historical, not the active runtime security mechanism.

Reuse reviewed ChatGPT Studio browser lifecycle/profile/snapshot patterns where appropriate. Do not copy chat composer/draft capture or all workflow features into an X post observer. Preserve Xs database investment. Code reuse rights and third-party notices must follow docs/LICENSING.md.

## Identity, migration and concurrency

Default to one explicit app-owned local library, with optional user-managed private spaces later. Browser profiles/cookies and library ownership are separate. The legacy bindAuthenticatedAccount contract must never receive a scraped username or local UUID while pretending it is authenticated.

Do not flatten old account_id namespaces or silently merge their data. Design/test a preserving migration with legacy mappings and explicit consolidation choices. Actor identity can annotate own actions; it is not a prerequisite for local favorites of public content.

Native code owns scope, epoch, navigation IDs, receipt clocks, consent and work budgets. Use a unified bounded write controller; recheck invalidation at write time. Scope changes, clear, cancellation and old import/download/share callbacks must not resurrect deleted data. Sharing requires a bounded read lease/export copy so selected files are not deleted before the receiver can read them.

## Web and file trust boundaries

Treat all page data as untrusted, including same-origin scripts. Exact origins, top-level frame, current route, schema, size, sequence, epoch and foreground state require validation. A page must not access arbitrary SQL, paths, intents, archive reads/exports/deletes or consent controls.

No credential, cookie/token export, DMs, drafts, hidden state or arbitrary network-response logging. WebView manages its normal session. Exclude login, verification, settings/security, DMs, composers and uncertain contexts from capture, including SPA transitions. No User-Agent impersonation or copied external cookies to bypass login restrictions.

No Xs cloud/analytics/remote executable rules without explicit approval. User-requested sharing intentionally hands selected content to another app; do not claim that content can never leave the device afterward.

Media resolution is scoped to authorized current content, not a generic network proxy. Validate redirects/destinations, generate filenames, bound size/retries/queue and use atomic completion. No DRM/payment/access-control bypass. Store signed URLs/credentials only as necessary transient request state, never public logs or share captions.

Expose only selected completed shareable assets via content URIs and temporary read permissions. Never expose database/session/key directories, arbitrary file paths or whole-library access. Do not mark choosing a Sharesheet target as message delivery.

## Work order and evidence

Borrow the existing browser foundation; implement/test offline post observation, favorites, media-state and share contracts in parallel with actual X login/device validation. W0 remains a production integration gate, not a ban on useful offline implementation. Do not turn the core product into a manual link organizer.

After relevant runtime edits:

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug :app-android:assembleDebug :app-android:lintDebug
gradle --no-daemon :dev-harness:connectedDebugAndroidTest :app-android:connectedDebugAndroidTest
```

Record exact commits, Android/WebView/recipient-app versions, MIME/size, test paths and outcomes. Docs-only changes do not claim new runtime validation. No universal login, multi-account isolation, media-format, background execution, direct-conversation target or store-release promises without evidence.

The owner permits a custom-license direction. Root LICENSE remains authoritative until separately finalized; do not invent commercial terms, revoke old grants, remove third-party attribution or call custom noncommercial licensing OSI open source.

Never deploy a site, touch the user's computer/servers, publish a production build or commit signing keys/private content without the appropriate explicit request. Keep migration, erasure, privacy and distribution gates honest.

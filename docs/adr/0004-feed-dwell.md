# ADR-0004: Optional feed dwell history without opening a post

Date: 2026-11-06
Status: Accepted product requirement. Runtime delivery and live-X compatibility are NOT complete.

## Context and user decision

A person may stop and read a post in the X timeline without opening its detail page. Xs should be able to remember these visible, sufficiently long exposures locally after independent opt-in, so the user can search and classify them later. **This is not a server bookmark, an app favorite, a deliberate post open or proof of reading.**

## Decision

- Maintain independent `OPEN_POST`, `FEED_VISIBLE`, `FAVORITE` and `LOCAL_SAVE` meanings.
- `FEED_VISIBLE` is optional (OFF by default) with selectable continuous dwell thresholds **1.2s / 3s / 5s**, recommended default **3s**. Keep the existing 1.2s eligibility floor; thresholds apply to feed only, never to immediate post opens.
- Track actual foreground, unoccluded, readable, continuously visible time, with a baseline 60% readable-area criterion that accommodates taller-than-viewport posts.
- Preserve measured dwell duration, exposure provenance and independent post/content snapshots; support **dwell >= N seconds AND never opened** filters locally.
- One continuous exposure = one event. Re-render is not a new event. Genuine re-entry can be another exposure; duplicate suppression must not silently destroy real distinct visits.
- App-owned library persists across X logout/switches. Scope/document/navigation generation is native-controlled, and invalidation is checked before persistence. No private-message or credential observation.
- Move baseline XS-14 **earlier into batch B** soon after immediate-open history; defer only heavy optimization/advanced analytics to D.

## Implications

Add an X-specific observer plus bounded, native-validated event flow. Existing SQLite v2 is not proof that event duration/search already exists; design a retaining migration with tests. Run synthetic coverage before live activation. Live X login, route/source verification and device acceptance remain mandatory.

See [dedicated specification](../FEED_DWELL_PLAN.zh-CN.md), [product](../product.md), [implementation](../IMPLEMENTATION_PLAN.zh-CN.md) and [acceptance D01–D09](../ACCEPTANCE_MATRIX.zh-CN.md).

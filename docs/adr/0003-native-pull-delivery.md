# ADR-0003: Reuse the browser foundation and prefer native-pull observations

Date: 2026-11-06. Status: accepted implementation plan for the confirmed product scope; runtime migration is NOT delivered by this ADR.

## Context and authority

The owner points to the usable Android ChatGPT Studio shell and confirms app-owned history/favorites/media independent of X accounts. [ADR-0002](0002-local-library-media.md) defines product ownership and event semantics. This ADR consolidates how to implement those requirements without restarting the product or waiting indefinitely on foundational plumbing.

## Decision

1. Preserve the Xs Android project, SQLite/FTS archive, migrations and native UI. Review browser lifecycle, Profile, controlled popup/navigation and generation-cancellation patterns from the pinned ChatGPT Studio source. Do not copy its entire workflow, chat composer/draft capture, service domains, package identity or signing keys.
2. Prefer a packaged site-specific observer with native-initiated, bounded evaluateJavascript reads for the first post-history loop. All returned data remains untrusted. A generic bidirectional bridge is not a prerequisite; any later message transport must preserve the same consent, origin, current-route, epoch and write-time checks.
3. A deliberate successful detail navigation creates OPEN_POST immediately after opt-in, with no dwell gate. Persist LINK_ONLY when body text is not ready, then enrich without creating more opens. Use native navigation identity, not only increasing message sequences.
4. Browser profile/session and local library ownership are independent. Default to one app library; X logout/switching invalidates old observation context but does not delete or silently select a different library. Own-action attribution may remain unknown; local favorites do not require a verified X identity.
5. Implement offline fixtures, migration, favorites, downloads and sharing in parallel with live browser validation. W0 remains a live deployment/acceptance gate, not a ban on useful offline implementation. Do not enable unvalidated automation in the real-site build or claim login support from fixtures.
6. Use a unified bounded native write/download-completion controller. Check generation again at persistence. Separate open/feed consent and history/favorite/asset/cache/session deletion semantics. Outbound shares are explicit actual-file grants, not account integrations or automatic messaging.

## Consequences

Existing inactive ObservationGate/Parser are reusable foundations, not a completed live listener. They require review of epoch reuse, per-feature consent, route/navigation identity, strict parsing and bounded work before activation. ChatGPT's periodic snapshot cadence is not sufficient to guarantee capture of a quick open-and-return; X needs its own navigation events and bounded flush behavior.

This supersedes ADR-0001/legacy plan assumptions only where they prescribe account-owned archives, a particular message-listener-first sequence, or prohibit all offline work until W0. Provider restrictions, no credential copying, no sensitive-page capture, evidence discipline and preserving migrations remain unchanged.

## Execution

[Implementation plan](../IMPLEMENTATION_PLAN.zh-CN.md), [reuse manifest](../CHATGPT_STUDIO_REUSE.zh-CN.md), [acceptance matrix](../ACCEPTANCE_MATRIX.zh-CN.md), [architecture](../architecture.md). Root LICENSE remains effective until the separately recorded [licensing work](../LICENSING.md) is finalized; permission to consider a custom license is not an automatic license replacement.

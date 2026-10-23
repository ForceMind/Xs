# Xs product requirements — confirmed scope

## Identity and non-goals

Xs enhances the installed X Android client. It is not a separate reader, an X API
proxy, a self-hosted website, an alternative backend, or a standalone account manager.

Preserve X's primary features: account session, feed, posts, replies, reposts,
bookmarks, media, notifications and DMs. We only add optional local features.

## M1 features

1. Clean UI: disable promotional cards and Premium upsells using available
   version-compatible patch options. Do not bypass purchased feature gates.
2. Post-open history: only count an intentional transition to post details.
   Do **not** call feed impressions or background prefetches an 'open'.
3. Timeline: store every distinct open event with precise timestamp and source.
   Multiple visits to the same post remain separate events; the post is deduplicated.
4. Personal content: distinguish original/reply/quote from repost actions.
   Publication time, repost time, and read time are separate nullable fields.
5. Local search: full-text/indexed search for simple Latin terms and substring
   fallback for CJK and punctuation; filter by account, category and time basis.
6. Local organization: add user-defined folders/tags and notes without editing X.

## Data and default UX

- Recording is an explicit per-account user preference; no remote service.
- Default only deliberate post details; optional feed impression recording may
  come later with a clear switch and separate event type.
- A date view may group by visit date or content publish date. Unknown
  publication dates remain unknown and must not be synthesized.
- Search results open the original X post; missing/deleted posts can still
  retain saved local metadata.
- Include clear-history, export and import controls in the future UI.
- No account passwords, bearer tokens, cookies, DM bodies, private-account
  scraped data, or unrelated app traffic in the local index.
- Account key is a stable authenticated account ID, never a display handle;
  switching accounts must not mix histories.

## Acceptance criteria for a first usable APK

- Runs on a pinned, explicitly verified X build.
- Opens a post and creates exactly one local OPEN_POST event per actual action.
- Returning to the feed does not create another open event.
- Reopening the same post creates a second event, but one post record.
- History and search work offline and do not leak across local X accounts.
- Category changes persist after force-close/restart.
- Clean-up controls do not remove original X account data.
- No extra background upload or authentication interception.

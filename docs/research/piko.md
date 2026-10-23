# Piko code-structure review

Source: https://github.com/crimera/piko
Inspected: 2026-10-24
Revision: a0ed9a5ae782fe66e02294e5a49d235e2f139b24, v3.10.0
License: GPL-3.0 with an additional GPLv3 §7(b) NOTICE retention requirement.

## Verified project layout

- settings.gradle.kts: app.morphe.patches Gradle settings plugin v1.3.4.
  Morphe GitHub Packages registry may require credentials for dependencies.
- gradle/libs.versions.toml: Morphe patcher 1.14.1, patches/extension library 1.6.0,
  and Android-related libraries are referenced.
- patches/src/main/kotlin/app/crimera/patches/twitter:
  target-version compatibility, fingerprints, bytecode/resource patch definitions.
- extensions/twitter/src/main/java/app/morphe/extension/twitter:
  runtime Java extension, app hooks, settings and feature behavior.
- extensions/shared: common extension pieces.
- patches/src/main/resources/addresources: injected settings strings and resources.
- .github, gradle, patches-bundle.json, patches-list.json:
  build, packaging and release management.

Piko is **not** a standalone replacement X implementation. Its patches target the
original Android app package and add runtime behavior via extension hooks.

## Concrete source mechanisms inspected

1. Twitter compatibility:
   patches/.../twitter/utils/Constants.kt defines com.twitter.android,
   APKM, target version 12.19.1-release.0.
2. Ads:
   patches/.../twitter/ads/timelineEntryHook/HideAds.kt defines the
   'Remove Ads' bytecode patch. It depends on timelineEntryHookPatch and
   settingsPatch, coordinates flagSettings('hideGoogleAds') and hooks
   runtime TimelineEntry.hidePromotedTrend.
   extensions/.../patches/TimelineEntry.java filters timeline entry IDs and
   returns null for selected promoted/recommendation entries.
3. Premium UI:
   patches/.../twitter/timeline/removePremiumUpsell/RemovePremiumUpsellPatch.kt
   locates a subscription-flag-related method and inserts a preference call.
4. Preferences and settings UI:
   extensions/.../settings/Settings.java / ScreenBuilder.java and
   patches/.../twitter/misc/settings/SettingsPatch.kt. Runtime Java behavior is
   separately guarded by setting/status values.
5. Runtime patching:
   the patch layer edits bytecode to invoke extension methods; the runtime
   layer operates on app process objects. A screen cannot be added merely
   by adding an HTML UI or a database.

## Reuse decision

| Feature | Approach |
| --- | --- |
| Ad and promoted cards | Reuse upstream Piko patch bundle/configuration after independent build and device verification |
| Premium upsell | Reuse upstream implementation after compatibility checks; do not unlock paid entitlements |
| Navigation/sidebar | Investigate Piko customization hooks; Xs history entry not wired yet |
| Post-open event | New Xs behavior: identify actual post-detail routing callback, not feed rendering |
| Local archive | New Xs Java/Android SQLite extension; records no credentials or DMs |
| Search/categories | New Xs local UI/API; version-independent database layer |

Do not copy all of Piko or vendor its bundled binaries. Keep upstream pinned as
source code dependency retrieved on demand, then integrate our extension on top.
If Piko files are copied or modified later, retain upstream copyright headers,
GPLv3 LICENSE and original NOTICE.

## Risks and research not yet performed

- No APKM or runtime X session was provided; post-detail lifecycle fingerprints
  have not been identified or validated on a physical device.
- Obfuscated field names and fingerprints can change between X releases.
- Login, signature, Play Store updates and XChat may be affected by patched APKs.
- Ad UI hiding must not be described as an existing Xs binary capability.
- Piko has a 'Log server response' patch, but Xs should **not** enable broad
  network logging, token collection or private message capture.
- Avoid disabling authentication, payment verification or security protections.

Source paths are case-sensitive; the pinned commit is necessary for reproducibility.

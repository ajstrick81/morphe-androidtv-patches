# Bytecode Patching Techniques — Field Guide

A working reference for how this repo patches Android TV apps, and where we can
level up. It is built from a full read of the Morphe/ReVanced patch codebase
(the upstream "gold standard") compared against how our patches are written
today.

This is a living document. When you learn a new technique or retire an old one,
update the relevant section so the next patch — and the next person — starts
from current knowledge.

---

## How to read this document

- **Part 1** maps what we *already do well* to the vocabulary the upstream
  project uses, so we can talk about our own code precisely.
- **Part 2** is the core mental model: the **hook / extension split**.
- **Part 3** is the toolbox — the helper functions that already ship in the
  library we compile against, several of which we are currently hand-rolling.
- **Part 4** covers the bigger architectural techniques we have *not* adopted
  yet, with an honest note on which ones actually apply to our apps.
- **Part 5** is conventions: settings, shared factories, provenance, versioning.
- **Part 6** is a prioritized roadmap.

Throughout, `Ours:` blocks are real code from this repo and `Morphe:` blocks are
real code from theirs, so the comparison is concrete rather than abstract.

A note on the toolchain, because it changes what's "available" vs "a rewrite":
we apply the **same Gradle plugin** as upstream — `id("app.morphe.patches")` —
so we compile against the same patcher API they do. Everything in Part 3 lives
in that library family (imported as `app.morphe.patcher.*` and
`app.morphe.util.*`). The practical consequence: most of the toolbox below is a
one-line `import` away, not a dependency we have to go find. If a specific
helper isn't resolvable on our pinned plugin version, it will be in a nearby
version — treat "bump the plugin" as cheaper than "reimplement the helper."

---

## Part 1 — What we already do well

It's worth stating plainly, because it sets the baseline: our patches are not
naive. We already use several of the techniques that make upstream robust. Named
in their vocabulary:

### 1.1 Structural fingerprints (not blind opcode matching)

We identify target methods by **what they are** — defining class, method name,
parameter types, return type — using the `custom` predicate. That is exactly the
resilient style upstream leans on.

`Ours:` (`peacock/ads/Fingerprints.kt`)
```kotlin
internal object HandleAdBreakStartedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Lcom/comcast/helio/subscription/AdBreakStartedEvent;"),
    custom = { method, classDef ->
        method.name == "handleAdBreakStarted" &&
            classDef.type == "Lcom/sky/core/player/sdk/playerEngine/playerBase/PlayerEngineItemImpl;"
    },
)
```

This is the same idea as their `custom = { _, classDef -> classDef.superclass == "Landroid/app/Dialog;" }`.
When a method has no stable string anchor, keying on a uniquely-typed parameter
(as we did above — `AdBreakStartedEvent` appears in exactly one signature) is a
legitimately strong fingerprint. Keep doing this.

### 1.2 Java extensions carrying the real logic

We already ship compiled helper classes inside the patched APK and call into
them from injected bytecode. That is the single most important architectural
decision upstream makes, and we're already on the right side of it.

`Ours:` (`peacock/ads/SkipAdsPatch.kt`)
```kotlin
extendWith("extensions/extension.mpe")
// ...
invoke-static {$registerName}, Lajstrick81/morphe/extension/peacock/ads/PeacockWebViewHelper;->wrapClient(...)
```

`PeacockWebViewHelper`, `PeacockAdPatchHelper`, and `AdBlockInterceptor` are our
extension classes — ordinary Java doing the real work (OkHttp client
construction, `shouldInterceptRequest` wrapping), with the bytecode patch only
placing the call. This is precisely the model in Part 2.

### 1.3 Dynamic offset & register resolution

We don't trust hardcoded instruction indices. `wrapXtvClientSetter` locates the
`setWebViewClient` call by scanning for it and computes the holding register at
runtime, which is *why* our Peacock patch survived the v7.5.102 → v7.6.100 field
removal that shifted every constructor's offsets by −4.

`Ours:`
```kotlin
val setClientIndex = instructions.indexOfFirst { instruction ->
    instruction.opcode == Opcode.INVOKE_VIRTUAL &&
        ((instruction as ReferenceInstruction).reference as? MethodReference)?.name == "setWebViewClient"
}
val clientRegister = (instructions[setClientIndex] as FiveRegisterInstruction).registerD
```

That instinct is correct and matches upstream discipline. Part 3.1 and 3.2 show
the library helpers that do this same job with less boilerplate and fewer edge
cases (the `p` vs `v` register-naming logic we wrote by hand is a solved problem
in the library).

**Takeaway:** we are not starting from zero. The gaps below are refinements and
additions on a sound foundation, not a rewrite.

---

## Part 2 — The core model: the hook / extension split

Every robust patch upstream is two parts with a hard line between them:

1. **The hook** — the smallest possible bytecode edit, ideally a single
   `invoke-static` into an extension method, or a single literal/return
   override. It exists only to *transfer control*.
2. **The extension** — a normal, compiled Java class inside the APK that holds
   all the actual behavior: conditionals, try/catch, Android API calls, logging,
   settings lookups.

Why the line matters:

- **Bytecode is write-only in practice.** A hand-written smali block is hard to
  read, hard to review, and brittle under app updates (register pressure,
  verifier rules, offset drift). Every instruction you *don't* write by hand is a
  bug you can't ship.
- **Java is testable and debuggable.** Logic in an extension can be reasoned
  about, logged, and changed without touching bytecode.
- **The hook rarely changes; the logic changes often.** Keeping them separate
  means app updates usually only require re-anchoring the hook, not rewriting
  behavior.

### The exemplar

Upstream's "hide fullscreen ads" patch. The entire meaningful bytecode edit:

`Morphe:` (`shared/ad/HideFullscreenAdsPatch.kt`)
```kotlin
addInstruction(index + 1,
    "invoke-static { v$register }, $EXTENSION_CLASS->hideFullscreenAds(Landroid/view/View;)V")
```

Everything else lives in Java — checking the user setting, resizing the ad
dialog to 0×0, restoring window flags, dismissing it, handling the YouTube-vs-
Music difference:

`Morphe:` (`HideFullscreenAdsPatch.java`, abridged)
```java
public static void closeFullscreenAd(Object customDialog, @Nullable byte[] buffer) {
    try {
        if (!SharedYouTubeSettings.HIDE_FULLSCREEN_ADS.get()) return;
        if (buffer == null) return;
        if (customDialog instanceof Dialog dialog && FULLSCREEN_AD_SEARCH.matches(buffer)) {
            Window window = dialog.getWindow();
            // ... resize to 0, clear dim, restore insets, dismiss ...
        }
    } catch (Exception ex) {
        Logger.printException(() -> "closeFullscreenAd failure", ex);
    }
}
```

### Where we stand, and the refinement

We already do this for the WebView/OkHttp layers (Part 1.2). The refinement is
to apply it **consistently**, especially to the layers currently expressed as
raw bytecode. Several of our Peacock layers no-op a method inline:

`Ours:`
```kotlin
SsaiConfigurationProviderFingerprint.method.addInstructions(0, """
    const/4 v0, 0x0
    return-object v0
""".trimIndent())
```

That's fine and correct — but "make this method return null/void/false" is such
a common operation that the library gives it a name (`returnEarly`, Part 3.3),
which is safer at the verifier level and clearer at the review level. The rule
of thumb going forward:

> **If a bytecode block is more than a hook, ask whether it should be an
> extension call or a named helper instead.** Raw multi-instruction smali is a
> last resort, not a default.

---

## Part 3 — The toolbox we already have (and partly reinvent)

These helpers live in the patcher library the `app.morphe.patches` plugin puts
on our classpath (`app.morphe.util.*`). Upstream uses them constantly; we mostly
hand-roll the same behavior. Adopting them shrinks our patches and removes
whole classes of edge-case bugs. Frequency counts below are how often upstream
uses each across their shared + YouTube patches.

### 3.1 `instructionMatches` — declarative instruction location

This is the highest-value, lowest-risk change we can make. Instead of a fingerprint
that only names the method and *then* a hand-written `indexOfFirst { ... }` loop
inside the patch, upstream puts the instruction-level matchers **in the
fingerprint** via `filters`, and reads back the matched indices with
`instructionMatches`.

`Morphe:`
```kotlin
// Fingerprint declares what instructions to find:
internal object LithoDialogBuilderFingerprint : Fingerprint(
    // ...
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "show"),
        resourceLiteral(ResourceType.STYLE, "SlidingDialogAnimation"),
    )
)

// Patch reads the result back, no manual loop:
val index = it.instructionMatches.first().index + 2
```

`Ours:` today, the equivalent is a manual scan:
```kotlin
val setClientIndex = instructions.indexOfFirst { instruction ->
    instruction.opcode == Opcode.INVOKE_VIRTUAL &&
        ((instruction as ReferenceInstruction).reference as? MethodReference)?.name == "setWebViewClient"
}
```

The `filters` version is worth adopting because the *matching logic lives with
the fingerprint it belongs to*, the cast-and-null-check boilerplate disappears,
and `instructionMatches[n].index` reads cleanly. The available filter builders
(with upstream usage counts) are:

| Filter | Uses | Matches |
|---|---|---|
| `methodCall(...)` | 221 | a method invocation by name / signature / return type / opcode |
| `opcode(...)` | 188 | a specific opcode (the low-level fallback) |
| `string(...)` | 127 | a string literal load |
| `literal(...)` | 106 | a numeric/const literal |
| `resourceLiteral(ResourceType.ID, "…")` | 99 | a reference to a named resource id/style/etc |
| `fieldAccess(...)` | 85 | a field read/write by type or name |
| `checkCast(...)` | 21 | a cast to a given type |
| `newInstance(...)` | 6 | construction of a given type |

For our `setWebViewClient` case, the fingerprint would carry
`filters = listOf(methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "setWebViewClient"))`
and the patch would read `it.instructionMatches.first().index` — deleting the
manual loop entirely while keeping the exact dynamic-offset resilience we value.

### 3.2 `findFreeRegister` — stop computing `p`/`v` names by hand

We wrote this by hand in `wrapXtvClientSetter`:

`Ours:`
```kotlin
val totalRegisters = method.implementation!!.registerCount
val paramRegisters = method.parameters.size + 1 // +1 for implicit `this`
val firstParamRegister = totalRegisters - paramRegisters
val registerName = if (clientRegister >= firstParamRegister) "p${clientRegister - firstParamRegister}" else "v$clientRegister"
```

`Morphe:` uses the library:
```kotlin
val freeRegister = findFreeRegister(insertIndex, insertRegister)
// and getFreeRegisterProvider(...) when several scratch registers are needed
```

`findFreeRegister(index, vararg registersToExclude)` returns a register that is
actually free at that program point — which is stronger than what our hand-rolled
version does, because ours only translates an *existing* register's name; it
doesn't find a *safe scratch* register for new values. Anywhere we need a
temporary (e.g. the `move-result-object` dance), `findFreeRegister` is the
correct tool and removes the possibility of clobbering a live value.

### 3.3 `returnEarly` / `returnLate` — neutralize a method cleanly

Making a method a no-op or forcing a constant return is our single most common
operation (Peacock Layers 3/4/5 all do it). We write the smali by hand each time.
The library names it, and handles the verifier-correct form for each return type:

`Morphe:`
```kotlin
method.returnEarly()          // return-void
method.returnEarly(false)     // return false
method.returnEarly(0)         // return 0
method.returnEarly(fromPackageName) // return a string constant
```

`Ours:` (three separate hand-written variants across the Peacock patch)
```kotlin
.addInstructions(0, "const/4 v0, 0x0\nreturn-object v0")   // Layer 3/4
.addInstructions(0, "return-void")                          // Layer 5
```

`returnEarly` collapses all of those into one intention-revealing call. Note
this is *exactly* the situation our own Peacock comments agonize over — the
VerifyError history at Layer 6 ("type=Undefined", "type=Conflict") is the kind of
verifier subtlety these helpers are written to get right. Prefer the helper over
another hand-tuned offset.

### 3.4 `insertLiteralOverride` — flip a feature flag instead of removing code

When an ad path is gated behind a server/A-B boolean (very common in modern
streaming SDKs), the low-risk move is not to rip out the ad code — it's to find
the flag-returning instruction and override the literal it produces, optionally
routing it through an extension method so the user can toggle it:

`Morphe:` (`DisableDRCAudioPatch.kt`)
```kotlin
VolumeNormalizationConfigFingerprint.let {
    it.method.insertLiteralOverride(
        it.instructionMatches.first().index,
        "$EXTENSION_CLASS->disableDrcAudioConfig(Z)Z"  // extension decides the value
    )
}
```

We don't use this yet. It's directly relevant to us: any place we currently
disable a feature by no-oping a whole method could instead override the *flag*
that enables it — smaller blast radius, and it composes with a runtime toggle
(Part 5.1). Worth reaching for whenever the target is "a method that returns a
boolean/int that gates behavior."

### 3.5 `addInstructionsAtControlFlowLabel` — insert without breaking branches

When we insert at a raw index, we're implicitly assuming that index isn't the
target of a branch or the middle of a try-block. Upstream inserts at a
*control-flow label* so the new instructions are placed branch-safely:

`Morphe:`
```kotlin
addInstructionsAtControlFlowLabel(insertIndex, """
    move-object/from16 v$freeRegister, p1
    invoke-static { v$insertRegister, v$freeRegister }, $EXTENSION_CLASS->closeFullscreenAd(...)V
""")
```

For simple prepends at offset 0 (like most of our layers) this doesn't matter.
But for mid-method insertions — which is what Layer 7's `setWebViewClient`
injection is — it's the safer primitive than a bare `addInstructions(index, ...)`.

### 3.6 Smaller helpers worth knowing

- `getReference<MethodReference>()` / `getReference<FieldReference>()` — the
  typed, null-checked way to pull a reference off an instruction. We do the
  `(instruction as ReferenceInstruction).reference as? MethodReference` cast by
  hand; this replaces it.
- `indexOfFirstInstructionOrThrow` / `...ReversedOrThrow` — scan for an
  instruction and throw a clear patch error if absent, instead of an
  `indexOfFirst` that silently returns −1 and blows up later as an
  `ArrayIndexOutOfBounds`.
- `findInstructionIndicesReversedOrThrow` — get *all* matching indices (useful
  when the same call appears several times and you must patch each).
- `cloneParameters()` / `cloneMutable()` — safely work on a copy of a method's
  parameter list / body when you're restructuring rather than appending.

None of these change *what* our patches do; they make the *how* shorter and
harder to get subtly wrong. The migration is mechanical and can be done one
patch at a time.

---

## Part 4 — Larger techniques, and whether they apply to us

Not everything upstream does is relevant to our target apps. Being honest about
that is part of the value here.

### 4.1 The Litho component filter — powerful, but check applicability first

Upstream's flagship ad-blocking mechanism. Feed-based UIs (YouTube's home/feed)
render list items through the **Litho** framework from serialized protobuf.
Rather than patch each ad surface, they patch the render path **once** to route
every component through a central dispatcher (`LithoFilterPatch`), then express
each thing-to-hide as a small declarative `Filter`:

`Morphe:` (`AdsFilter.java`, abridged)
```java
final var generalAds = new StringFilterGroup(
    Settings.HIDE_GENERAL_ADS,
    "_ad_with", "ads_video_with_context", "banner_text_icon",
    "brand_video_shelf", "carousel_footered_layout", /* ...dozens more... */
);
addPathCallbacks(generalAds);
```

Matching is done with a **trie** (`StringTrieSearch` / `ByteTrieSearch`) so
hundreds of patterns are checked in effectively one pass over the buffer. A new
ad format becomes a one-line pattern addition, not a new patch.

**Does it apply to us?** Our apps (Peacock/Sky SDK, Prime, Disney+, etc.) are
mostly *not* Litho — they're native players and SDK-driven ad pipelines, which is
why our patches target `getSsaiConfigurationProvider`, FreeWheel DI wiring, and
WebView interception instead of feed components. So the Litho machinery itself is
largely N/A. **But the *pattern* generalizes:** "one central hook that sees all
candidates + a declarative list of what to suppress + fast multi-pattern
matching" is exactly what our `AdBlockInterceptor` already is for network
traffic. If we ever add a second app that renders ads through a component/list
framework, this is the model to copy. Until then, note it and move on.

### 4.2 Byte/string trie search — relevant to our interceptors

`ByteTrieSearch` (multi-pattern matching over a `byte[]`, used in the extension
to scan protobuf buffers) *is* relevant to us independent of Litho. Our
`AdBlockInterceptor` and `PeacockWebViewHelper` decide what to block; if that
decision is currently a series of `String.contains(...)` checks, a trie is both
faster and cleaner when the blocklist grows. Worth a look when the host list gets
long.

### 4.3 Localization / `AddResourcesPatch` — only if we add UI

Upstream has a full localized-string pipeline (≈75 locales, Crowdin-synced, with
a sanitizer that scrubs strings that would fail `aapt`). This only becomes
relevant if we add a settings UI (Part 5.1) with user-facing text. Flagged for
completeness; not a priority while our patches are headless.

---

## Part 5 — Conventions worth adopting

### 5.1 A runtime settings framework (headless → toggleable)

Right now every behavior we ship is hardcoded on. Upstream makes every feature a
typed setting with a generated preferences screen:

`Morphe:`
```kotlin
// In the patch:
preferenceScreen.addPreferences(SwitchPreference("morphe_hide_fullscreen_ads"))
```
```java
// In the extension, a typed, persisted setting with default + reboot flag:
public static final BooleanSetting HIDE_FULLSCREEN_ADS =
    new BooleanSetting("morphe_hide_fullscreen_ads", TRUE);
```

The payoff for a **test box** is real: one patched APK whose behavior you flip on
the device, instead of rebuilding to toggle a layer. Given our new `testing/`
harness, this pairs naturally — patch once, A/B a layer on the Onn TV without a
re-patch cycle. This is a larger lift (it pulls in the settings + preference
infrastructure), so it's a "when we're ready" item, not a quick win. But it's the
single biggest quality-of-life change available to us.

### 5.2 Parameterized shared patch factories

We currently have a `SkipAdsPatch.kt` per app, each a bespoke `bytecodePatch { }`.
Upstream writes a shared behavior **once** as a factory and calls it per app with
different wiring:

`Morphe:`
```kotlin
internal fun hideFullscreenAdsPatch(preferenceScreen: BasePreferenceScreen.Screen) =
    bytecodePatch(description = "...") { /* shared body */ }

// YouTube and Music each call it with their own preference screen / fingerprints.
```

Where two of our apps share a mechanism (e.g. several use OkHttp interception, or
the same SSAI-null trick), the interceptor/no-op logic could live in one
parameterized factory taking the app-specific fingerprints as arguments. This cuts
the copy-paste that currently means a fix to a shared technique has to be applied
in N files. Not urgent, but the right shape as the app count grows.

### 5.3 `dependsOn` — compose patches instead of one monolith

Upstream composes: a patch declares `dependsOn(resourceMappingPatch, settingsPatch, ...)`
and the framework orders execution. Our patches are largely self-contained, which
is fine at our size, but if we adopt shared resource/settings patches (5.1), this
is how the pieces connect. Keep in mind as we grow.

### 5.4 Provenance & licensing discipline

Upstream carries a per-file GPLv3 §7 header, and every forked file cites the exact
upstream commit it came from:

```
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61...
```

We are a fork too. Adding, at minimum, a one-line provenance comment to files we
port from upstream keeps the lineage auditable and the license obligations clear.
Cheap to do, annoying to reconstruct later.

### 5.5 Version-aware fingerprints as code, not comments

Our version knowledge currently lives in comments ("Confirmed matching v7.5.102
and v7.6.100"). That's genuinely useful documentation and we should keep it. The
next step upstream takes is to make version handling *executable*: multiple
fingerprints as fallbacks for different app versions, and a runtime version check
(`VersionCheckPatch`) so a single bundle spans releases and *fails loudly* on an
untested version rather than silently mispatching. Worth considering once a patch
needs to support more than two versions at once.

---

## Part 6 — Prioritized roadmap

Ordered by value-to-effort. None of this is urgent; it's the direction of travel.

| # | Change | Effort | Payoff |
|---|--------|--------|--------|
| 1 | Adopt `returnEarly` / `getReference` / `indexOfFirstInstructionOrThrow` in existing patches | Low | Shorter, safer patches; fewer silent −1 index bugs |
| 2 | Move instruction location into fingerprint `filters` + `instructionMatches` | Low–Med | Matching logic lives with the fingerprint; deletes manual scan loops |
| 3 | Use `findFreeRegister` / `addInstructionsAtControlFlowLabel` for mid-method inserts | Low–Med | Removes hand-rolled register math; branch-safe insertion |
| 4 | Prefer `insertLiteralOverride` over method no-op where the target is a gating flag | Med | Smaller blast radius; composes with runtime toggles |
| 5 | Extract shared mechanisms (OkHttp interception, SSAI-null) into parameterized factories | Med | One fix propagates to all apps that share the technique |
| 6 | Trie-based blocklist in `AdBlockInterceptor` if the host list keeps growing | Med | Faster, cleaner matching at scale |
| 7 | Runtime settings framework (`SwitchPreference` + typed settings) | High | Toggle layers on-device without re-patching — big win with the `testing/` harness |
| 8 | Executable version-aware fingerprints + runtime version check | High | One bundle spans app versions; fails loud on untested builds |

### The two-sentence version

We already do the two hardest things right: **logic lives in Java extensions**,
and **fingerprints match structure, not offsets**. The near-term wins are all
about *stopping the hand-rolling* — the library we already compile against has
named, verifier-correct helpers (`returnEarly`, `findFreeRegister`,
`instructionMatches`, `insertLiteralOverride`) for the exact operations we write
by hand today. The longer-term wins are a runtime settings framework and shared
factories, which matter more as the app count and the `testing/` loop grow.

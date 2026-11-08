package ajstrick81.morphe.patches.future.ads

import app.morphe.patcher.Fingerprint

// ─────────────────────────────────────────────────────────────────────────────
// Fawesome Movies & TV — com.future.moviesByFawesomeAndroidTV 20.8 — ad seams.
//
// 20.8 is NOT "20.1 with new letters". Between 20.1 and 20.8 the app was rebased
// onto Future Today's shared FilmRise MVVM skeleton: the whole com/future/* tree
// is gone (only com/future/datamanager survives) and the app now lives in
// readable packages again —
//
//     com/mvvm/videoplayer/VideoPlayerActivity   (was .../MediaPlayerActivity)
//     com/mvvm/splash/SplashActivity             (was .../SplashActivity)
//     com/recipe/filmrise/GlobalObject           (was com/future/util/GlobalObject)
//
// so structurally 20.8 is much closer to 19.7 than 20.1 ever was. CLASS names are
// preserved (they are named in the manifest / reached reflectively), but MEMBER
// names are R8-renamed to single letters and everything else is flattened into
// root-level one/two-letter packages. So we anchor on the readable class name plus
// the app's own log strings, which R8 cannot rename — never on the method letters,
// which change on every rebuild.
//
// There are TWO independent ad systems in 20.8:
//
//   A. "InhouseAds" — the app's own ad pods, held in GlobalObject.c0 (ArrayList)
//      and gated by VideoPlayerActivity.k0(String):Z, the ad-decision method.
//   B. IMA — the bundled Google IMA SDK, driven by the ad manager (ni/g in 20.8)
//      whose q(...) method requests and plays the pod.
//
// Both are forced down the app's OWN no-ad path, which is the same principle that
// made 19.7 work (force playAd's stock :cond_8 branch) and the same one that made
// Pluto 17.x work (force shouldPlayAd() to false) — never suppress the display
// call, which is what froze the player in earlier attempts.
// ─────────────────────────────────────────────────────────────────────────────

// Hook 1 — Lcom/mvvm/videoplayer/VideoPlayerActivity;->k0(Ljava/lang/String;)Z
//
// The in-house ad-decision method. Its parameter is the transition that asked
// ("Start", "Next", "Prev", "Repeat", "Selected", "SelectedFromList"), and it
// answers whether an ad should play now:
//
//     true  -> calls l1(String) (builds the in-house ad playlist from
//              GlobalObject.c0) and logs "Ad Status true First Time Ad" /
//              "Ad Status true Normal Ad"
//     false -> logs "Ad Status false Ads list is Empty" / "Ad Status false"
//
// All six call sites consume it as a plain branch — five as `if-eqz -> :cond_N`
// (false jumps to the content path) and the pre-roll one ("Start") as
// `if-nez -> :cond_8` (false falls through to the content path). Its own
// exception handler also returns false. So returning false at entry is exactly
// the app's stock "this title has no ads" verdict: no ad playlist is built, no
// ad is requested, and content loads/resumes natively.
//
// Anchored on the two no-ad log strings, which are unique to this method.
object InhouseAdDecisionFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf("Ad Status false Ads list is Empty", "Ad Status true Normal Ad"),
    custom = { _, classDef ->
        classDef.type.endsWith("/VideoPlayerActivity;")
    },
)

// Hook 2 — L<ad manager>;->q(L<models>;Lcom/mvvm/model/ObjectVideo;L<pod>;Ljava/lang/String;)V
//          (ni/g->q(Lni/h;Lcom/mvvm/model/ObjectVideo;Lni/a;Ljava/lang/String;)V in 20.8)
//
// The IMA ad-request entry point, called from the tail of
// VideoPlayerActivity.G1(String) once the content load has been set up.
//
// IMPORTANT: do NOT hook G1 itself. Despite ending in the ad call, G1 is the
// *content* loader — its six callers are all ordinary playback transitions
// ("AtStart", "NextVideo", "PrevVideo", "NextRow", "AfterDRM", "AfterStart"),
// and q1(String) ("play the requested video", the target of the app's own
// ad-failure fallback) itself calls G1("AfterStart"). Bare-returning G1 would
// stop playback entirely — the same trap that made hooking showMidRollAd fail
// on 19.7.
//
// q()'s third parameter is the ad pod (ni/a). Its first branch is
// `if-eqz p3, :cond_4`, and :cond_4 is a plain `return-void` — the app's own
// "no ad pod for this title" path. The assignments before that branch (the
// transition tag, GlobalObject.c2 = false, the player/view wiring) still run, so
// nulling the pod parameter at entry is a one-instruction, flow-preserving way
// to reach the stock no-ad outcome: no AdsRequest is built and no IMA call is made.
//
// The class and method letters are not stable, so anchor on the pod-count log
// strings this method builds, plus its shape: an instance (…, ObjectVideo, …,
// String)V method. The ObjectVideo parameter type survives because com/mvvm/model
// is a readable package.
object ImaAdRequestFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("No of Ads to be played", "No of Fuse Ads to be played"),
    custom = { method, _ ->
        method.parameters.size == 4 &&
            method.parameters[1].type == "Lcom/mvvm/model/ObjectVideo;" &&
            method.parameters[3].type == "Ljava/lang/String;"
    },
)

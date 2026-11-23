package ajstrick81.morphe.patches.youtubetv.misc.scripts

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS = "Lajstrick81/morphe/extension/youtubetv/scripts/TvScripts;"
private const val OBSERVER_CLASS = "Ldev/cobalt/shell/StartupGuardNavigationObserver;"
private const val WEB_CONTENTS = "Lcobalt/org/chromium/content_public/browser/WebContents;"

// Injector patch for the youtube.com/tv (Cobalt) web app. It is deliberately unnamed: PatchLoader
// skips unnamed patches, so this never shows up as its own checkbox in the Manager and only runs
// as a dependency of "Skip ads" (the reference repo wires its hook-only patches the same way).
//
// Why the navigation observer: Cobalt runs the leanback web app in-process, so the only way to
// strip ads that are decided in JavaScript is to run a script inside the page. The observer's
// constructor receives the page's WebContents, whose native pointer, together with the R8-stripped
// Java wrapper's JNI stub in org.jni_zero.GEN_JNI, reaches WebContentsImpl.evaluateJavaScript
// (see TvScripts.java). Shell registers the observer in initFromNativeTabContents before the start
// page loads, so its callbacks are the earliest page-lifecycle hooks a patch can use.
//
// The observer normally stops observing after the first navigation (observe(null)); removing that
// call keeps it attached so every later page load is hooked too.
internal val tvScriptsPatch = bytecodePatch {
    // merge into the patched APK's dex — without this the invoke-static targets below dangle.
    extendWith("extensions/extension.mpe")

    execute {
        val finish = StartupGuardFinishNavigationFingerprint.methodOrNull
            ?: throw PatchException(
                "$OBSERVER_CLASS->didFinishNavigationInPrimaryMainFrame(NavigationHandle)V not found",
            )
        val redirect = StartupGuardRedirectNavigationFingerprint.methodOrNull
            ?: throw PatchException("$OBSERVER_CLASS->didRedirectNavigation(NavigationHandle)V not found")

        // 1) Remember the page's WebContents. p1 is the WebContents parameter; it is initialised on
        //    entry, so it can be read at index 0, before the constructor's super call. The method
        //    has .registers 2 (.ins 2), i.e. exactly this (p0) and the parameter (p1).
        val constructor = StartupGuardObserverConstructorFingerprint.methodOrNull
            ?: throw PatchException("$OBSERVER_CLASS-><init>(WebContents)V not found")
        constructor.addInstructions(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->setWebContents(Ljava/lang/Object;)V",
        )

        // 2) Drop observe(null) so the observer stays attached after the first navigation. Both
        //    methods contain exactly one observe(WebContents) call, which passes the null from
        //    const/4 v2, #int 0. The now-unused const/4 is left in place: it is dead but harmless
        //    and removing it would mean also re-mapping the instruction indices.
        listOf(
            finish to "didFinishNavigationInPrimaryMainFrame",
            redirect to "didRedirectNavigation",
        ).forEach { (method, methodName) ->
            val instructions = method.implementation!!.instructions
            val indices = instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let { reference ->
                        reference.name == "observe" &&
                            reference.parameterTypes.singleOrNull()?.toString() == WEB_CONTENTS
                    } == true
            }.map { it.index }
            if (indices.isEmpty()) {
                throw PatchException("observe(null) call not found in $OBSERVER_CLASS->$methodName")
            }
            indices.reversed().forEach { method.removeInstruction(it) }
        }

        // 3) Inject the script when the first navigation finishes. The method has .registers 3
        //    (.ins 2), so v0 is a local register; the body does not read it before overwriting it,
        //    which makes clobbering it at index 0 safe.
        finish.addInstructions(
            0,
            """
                const-string v0, "didFinishNavigation"
                invoke-static { v0 }, $EXTENSION_CLASS->onPageEvent(Ljava/lang/String;)V
            """,
        )

        // 4) Add the two callbacks Cobalt dispatches on its NavigationObserver base class (Lbdi;)
        //    but which this observer does not override. primaryMainDocumentElementAvailable gives
        //    an earlier injection point than didFinishNavigation (the page's scripts may already
        //    have parsed a response by then); titleWasSet is the only channel from page JS back to
        //    logcat, because evaluateJavaScript discards the script's return value.
        val observer = mutableClassDefByOrNull { it.type == OBSERVER_CLASS }
            ?: throw PatchException("$OBSERVER_CLASS not found in the dex classes")

        fun addObserverOverride(name: String, parameterTypes: List<String>, body: String) {
            // PUBLIC FINAL matches the observer's other overrides. One local (v0) plus this and
            // the parameters is enough for both bodies (the title one only touches p1).
            val method = MutableMethod(
                ImmutableMethod(
                    observer.type,
                    name,
                    parameterTypes.map { ImmutableMethodParameter(it, null, null) },
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(1 + 1 + parameterTypes.size),
                ),
            )
            method.addInstructions(0, body)
            observer.methods.add(method)
        }

        addObserverOverride(
            "primaryMainDocumentElementAvailable",
            emptyList(),
            """
                const-string v0, "documentElementAvailable"
                invoke-static { v0 }, $EXTENSION_CLASS->onPageEvent(Ljava/lang/String;)V
                return-void
            """,
        )
        addObserverOverride(
            "titleWasSet",
            listOf("Ljava/lang/String;"),
            """
                invoke-static { p1 }, $EXTENSION_CLASS->onTitle(Ljava/lang/String;)V
                return-void
            """,
        )
    }
}

/*
 * yttv_tap.js — YouTube TV (Unplugged) capture script (frida-gadget, rootless)
 * See docs/YOUTUBE_TV_DASH_CAPTURE_PROCEDURE.md (Method 0 + Method A unpin).
 *
 * Two jobs:
 *   1) UNPIN — neutralize cert pinning in *native Cronet/BoringSSL* AND in the
 *      Java TLS stack, so a mitmproxy/AdGuard proxy (with the user_security_config
 *      user-CA) can decrypt the manifest + player-response fetches.
 *   2) TAP  — log, in-process and OBFUSCATION-PROOF, the payloads we care about:
 *      the DASH `.mpd` and the InnerTube `adSlotRenderer`/`cuepoint`/
 *      `InstreamAdBreak` JSON — by sniffing large decoded strings for our markers.
 *      No deobfuscation required for this to work.
 *
 * Load: shipped as lib/armeabi-v7a/libgadget.script.so via libgadget.config.so
 *       (script mode), or `frida -U -n <pkg> -l yttv_tap.js` in listen mode.
 */

'use strict';

var TAG = 'yttv-tap';
function log(s) { console.log('[' + TAG + '] ' + s); }

/* ───────────────────────── 1. Native Cronet / BoringSSL unpin ─────────────── */
// Chromium's verifier is native, so a Java TrustManager bypass alone is NOT
// enough for YouTube's media/player-response traffic. BoringSSL exposes
// SSL_[CTX_]set_custom_verify(ctx, mode, cb); we swap `cb` for one that always
// returns ssl_verify_ok (0). Falls back to forcing X509_verify_cert -> 1.
function unpinBoringSSL() {
    // ssl_verify_ok = 0 ; signature: enum (*cb)(SSL*, uint8_t* out_alert)
    var alwaysOk = new NativeCallback(function (/* ssl, out_alert */) {
        return 0;
    }, 'int', ['pointer', 'pointer']);

    var patched = 0;
    ['SSL_CTX_set_custom_verify', 'SSL_set_custom_verify'].forEach(function (name) {
        var p = Module.findExportByName(null, name);
        if (p === null) return;
        try {
            Interceptor.attach(p, {
                onEnter: function (args) {
                    // args[1] = mode; keep it, but replace the callback (args[2]).
                    args[2] = alwaysOk;
                },
            });
            patched++;
            log('unpin: hooked ' + name);
        } catch (e) { log('unpin: ' + name + ' attach failed: ' + e); }
    });

    // Belt-and-suspenders: force the classic path OK too, if present.
    var xvc = Module.findExportByName(null, 'X509_verify_cert');
    if (xvc !== null) {
        try {
            Interceptor.replace(xvc, new NativeCallback(function () { return 1; },
                'int', ['pointer']));
            patched++;
            log('unpin: forced X509_verify_cert -> 1');
        } catch (e) { log('unpin: X509_verify_cert replace failed: ' + e); }
    }

    if (patched === 0) {
        log('unpin: no BoringSSL symbols found yet — Cronet may load late. Retrying.');
        return false;
    }
    return true;
}

// Cronet is loaded from a split; it may not be present at t0. Poll briefly.
(function retryUnpin() {
    var tries = 0;
    var timer = setInterval(function () {
        tries++;
        if (unpinBoringSSL() || tries > 40 /* ~20s */) clearInterval(timer);
    }, 500);
})();

/* ───────────────────────── 2. Java-side unpin (cheap insurance) ───────────── */
function unpinJava() {
    // OkHttp CertificatePinner.check(...) -> no-op (any of it that ships).
    ['okhttp3.CertificatePinner', 'com.android.okhttp.CertificatePinner'].forEach(function (cn) {
        try {
            var CP = Java.use(cn);
            CP.check.overload('java.lang.String', 'java.util.List').implementation = function () {};
            log('java-unpin: neutralized ' + cn + '.check');
        } catch (e) { /* not present */ }
    });
    // Platform TrustManagerImpl.checkTrustedRecursive -> return the chain as-is.
    try {
        var TMI = Java.use('com.android.org.conscrypt.TrustManagerImpl');
        TMI.checkTrustedRecursive.implementation = function (certs, ocsp, tls, host, clientAuth, x, y) {
            return Java.use('java.util.ArrayList').$new();
        };
        log('java-unpin: neutralized TrustManagerImpl.checkTrustedRecursive');
    } catch (e) { /* varies by API level */ }
}

/* ───────────────────────── 3. Obfuscation-proof payload TAP ───────────────── */
// The manifest and ad payloads all pass through `new String(byte[]...)` when the
// app decodes a network body to text. We hook only the byte[] String
// constructors, and log ONLY large strings carrying our markers — so the hot
// path stays cheap and we don't need any obfuscated class names.
var MARKERS = [
    '<MPD',                    // DASH manifest
    'urn:scte:scte35',         // SCTE-35 EventStream in the MPD
    'adSlotRenderer',          // client-guided ad slot
    'cuepoint',                // the SCTE-driven seam
    'instreamAdBreak',         // built ad break
    'InstreamAdBreak',
    'adBreakRenderer',
    'playerAds',
];
var MIN_LEN = 256;
var seen = {};   // crude de-dupe by (marker + length)

function maybeDump(str) {
    if (!str || str.length < MIN_LEN) return;
    for (var i = 0; i < MARKERS.length; i++) {
        var m = MARKERS[i];
        if (str.indexOf(m) !== -1) {
            var key = m + ':' + str.length;
            if (seen[key]) return;
            seen[key] = true;
            log('HIT[' + m + '] len=' + str.length);
            // Write full body to the app's external files dir (pullable w/o root).
            try {
                var ctx = Java.use('android.app.ActivityThread')
                    .currentApplication().getApplicationContext();
                var dir = ctx.getExternalFilesDir(null).getAbsolutePath() + '/yttv_tap';
                Java.use('java.io.File').$new(dir).mkdirs();
                var f = dir + '/' + m.replace(/[^a-zA-Z0-9]/g, '_') + '_' +
                    str.length + '_' + Date.now() + '.txt';
                var fw = Java.use('java.io.FileWriter').$new(f);
                fw.write(str); fw.close();
                log('  wrote ' + f);
            } catch (e) {
                // Fall back to logcat (chunked) if file write fails.
                log('  (file write failed, dumping head) ' + str.substring(0, 400));
            }
            return;
        }
    }
}

function installTap() {
    var S = Java.use('java.lang.String');
    // new String(byte[])
    S.$init.overload('[B').implementation = function (b) {
        var r = this.$init(b);
        try { maybeDump(this.toString()); } catch (e) {}
        return r;
    };
    // new String(byte[], String charsetName) and (byte[], Charset)
    ['[B', 'java.lang.String'].forEach(function () {});
    try {
        S.$init.overload('[B', 'java.lang.String').implementation = function (b, cs) {
            var r = this.$init(b, cs);
            try { maybeDump(this.toString()); } catch (e) {}
            return r;
        };
    } catch (e) {}
    try {
        S.$init.overload('[B', 'java.nio.charset.Charset').implementation = function (b, cs) {
            var r = this.$init(b, cs);
            try { maybeDump(this.toString()); } catch (e) {}
            return r;
        };
    } catch (e) {}
    log('tap: String(byte[]) sniffers installed; markers=' + MARKERS.join(','));
}

/* ── Optional: once the disassembly pass gives you the obfuscated class, add a
 *    precise toString tap here instead of relying on the byte-string sniffer.
 *    Example (fill in the real name from classes3.dex):
 *      hookToString('xyz.Abc');  // the InstreamAdBreak / SelectionResult class
 */
function hookToString(className) {
    try {
        var C = Java.use(className);
        C.toString.implementation = function () {
            var s = this.toString();
            log('toString[' + className + '] ' + s);
            return s;
        };
        log('tap: hooked toString on ' + className);
    } catch (e) { log('tap: could not hook ' + className + ': ' + e); }
}

/* ───────────────────────── boot ───────────────────────── */
Java.perform(function () {
    unpinJava();
    installTap();
    log('ready. Watch: adb logcat | grep ' + TAG +
        '   — pull dumps: adb pull /sdcard/Android/data/<pkg>/files/yttv_tap');
});

// expose for listen-mode interactive use
rpc.exports = {
    addmarker: function (m) { MARKERS.push(m); return MARKERS; },
    hooktostring: function (c) { Java.perform(function () { hookToString(c); }); },
    reunpin: function () { return unpinBoringSSL(); },
};

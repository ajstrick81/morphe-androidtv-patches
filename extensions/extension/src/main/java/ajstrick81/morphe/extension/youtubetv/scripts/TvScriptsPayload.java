/*
 * Ad-strip payload injected into https://www.youtube.com/tv by TvScripts.java.
 *
 * The script is a hand-written Java string instead of a Gradle code-generation task so the
 * extension module stays a single javac step: no asset packaging, no generated sources.
 *
 * Ported from farotv-patches (GPLv3), extensions/extension/src/main/js/ads.js and runtime.js;
 * the JSON.parse hook idea comes from DylanDuff/utv-runtime (MIT).
 */
package ajstrick81.morphe.extension.youtubetv.scripts;

/**
 * The ad-strip script TvScripts evaluates inside the leanback page.
 *
 * It wraps JSON.parse once (window.__ytvAds in the page makes a second injection a no-op) and,
 * for every InnerTube response:
 *  - deletes adPlacements / adSlots / playerAds / adBreakHeartbeatParams anywhere in the tree,
 *    including a nested playerResponse;
 *  - removes sponsored feed items (adSlotRenderer and the promoted* renderers) from lists;
 *  - reports counts by setting document.title to "tvp:<message>", which the patched
 *    titleWasSet logs under the MorpheYTTV tag.
 *
 * Everything is inside try/catch and non-InnerTube JSON is returned untouched. The title is left
 * at "tvp:<message>" until the page sets it again (cosmetic only; the leanback UI shows no title).
 */
final class TvScriptsPayload {
    static final String SCRIPT =
        "// Strip YouTube TV ads at the JSON.parse boundary: every InnerTube response is parsed here\n" +
        "// before the player/feed code reads it, so deleting the ad schedule and the sponsored feed\n" +
        "// items takes effect without touching playback. Ported from farotv-patches ads.js/runtime.js\n" +
        "// (GPLv3), itself based on DylanDuff/utv-runtime (MIT).\n" +
        "(function () {\n" +
        "    if (location.hostname !== 'www.youtube.com' || window.__ytvAds) return;\n" +
        "    window.__ytvAds = true;\n" +
        "\n" +
        "    var AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams'];\n" +
        "    var AD_ITEMS = ['adSlotRenderer', 'inFeedAdLayoutRenderer', 'promotedVideoRenderer',\n" +
        "        'compactPromotedVideoRenderer', 'promotedSparklesWebRenderer'];\n" +
        "    var RESPONSE_KEYS = ['responseContext', 'contents', 'onResponseReceivedActions',\n" +
        "        'continuationContents', 'playerResponse', 'adPlacements', 'playerAds'];\n" +
        "    var MAX_DEPTH = 24;\n" +
        "\n" +
        "    var failureReported = false;\n" +
        "\n" +
        "    function owns(object, key) {\n" +
        "        return Object.prototype.hasOwnProperty.call(object, key);\n" +
        "    }\n" +
        "\n" +
        "    // Only touch InnerTube responses; unrelated JSON that happens to have an\n" +
        "    // \"adSlots\"-like key must come back unchanged.\n" +
        "    function isInnerTube(value) {\n" +
        "        for (var i = 0; i < RESPONSE_KEYS.length; i++) {\n" +
        "            if (owns(value, RESPONSE_KEYS[i])) return true;\n" +
        "        }\n" +
        "        return false;\n" +
        "    }\n" +
        "\n" +
        "    // Feed items that are ads. Seen on Home: adSlotRenderer wrapping an\n" +
        "    // inFeedAdLayoutRenderer, as the first tile of the first shelf.\n" +
        "    function isAdItem(item) {\n" +
        "        if (!item || typeof item !== 'object' || Array.isArray(item)) return false;\n" +
        "        for (var i = 0; i < AD_ITEMS.length; i++) {\n" +
        "            if (owns(item, AD_ITEMS[i])) return true;\n" +
        "        }\n" +
        "        return false;\n" +
        "    }\n" +
        "\n" +
        "    function strip(value, depth) {\n" +
        "        if (!value || typeof value !== 'object' || depth > MAX_DEPTH) return 0;\n" +
        "        var hits = 0;\n" +
        "        if (Array.isArray(value)) {\n" +
        "            for (var i = value.length - 1; i >= 0; i--) {\n" +
        "                if (isAdItem(value[i])) {\n" +
        "                    value.splice(i, 1);\n" +
        "                    hits++;\n" +
        "                } else {\n" +
        "                    hits += strip(value[i], depth + 1);\n" +
        "                }\n" +
        "            }\n" +
        "            return hits;\n" +
        "        }\n" +
        "        for (var k = 0; k < AD_KEYS.length; k++) {\n" +
        "            if (owns(value, AD_KEYS[k])) {\n" +
        "                delete value[AD_KEYS[k]];\n" +
        "                hits++;\n" +
        "            }\n" +
        "        }\n" +
        "        for (var key in value) {\n" +
        "            if (owns(value, key)) hits += strip(value[key], depth + 1);\n" +
        "        }\n" +
        "        return hits;\n" +
        "    }\n" +
        "\n" +
        "    // Cobalt drops evaluateJavaScript's return value, so the only observable\n" +
        "    // channel back to Java is the page title: titleWasSet is patched to log it.\n" +
        "    function report(message) {\n" +
        "        document.title = 'tvp:' + message;\n" +
        "    }\n" +
        "\n" +
        "    var parse = JSON.parse;\n" +
        "    JSON.parse = function () {\n" +
        "        var result = parse.apply(this, arguments);\n" +
        "        if (!result || typeof result !== 'object') return result;\n" +
        "        try {\n" +
        "            if (!isInnerTube(result)) return result;\n" +
        "            var hits = strip(result, 0);\n" +
        "            if (hits > 0) report('ads: removed ' + hits + ' ad object(s)');\n" +
        "        } catch (e) {\n" +
        "            if (!failureReported) {\n" +
        "                failureReported = true;\n" +
        "                report('ads: strip failed: ' + e);\n" +
        "            }\n" +
        "        }\n" +
        "        return result;\n" +
        "    };\n" +
        "\n" +
        "    report('ads: hook installed');\n" +
        "})();\n";

    private TvScriptsPayload() {
    }
}

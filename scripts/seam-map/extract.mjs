// Seam map extractor: reads the patch sources and writes a per-app map of
//   package + target versions -> patches -> fingerprints (the code seams) -> action taken
//   + the runtime log lines our extensions / native hooks emit (the evidence a seam fired).
// Regenerate after any patch change:  node scripts/seam-map/extract.mjs
// Output: docs/seam-map/seam-map.generated.json (consumed by render.mjs and check.mjs).
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const PATCH_ROOTS = ['patches/src/main/kotlin/ajstrick81/morphe/patches', 'patches/src/main/kotlin/app/morphe/patches'];
const EXT_ROOT = 'extensions/extension/src/main/java/ajstrick81/morphe/extension';
const NATIVE_GLOB = 'experimental'; // <app>-*/jni/*.cpp native hooks
const SKIP = new Set(['shared']);

const walk = (dir, out = []) => {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      if (!/^(build|build-[\w-]+|node_modules|\.git)$/.test(entry)) walk(path, out);
    } else out.push(path);
  }
  return out;
};
const rel = (path) => relative(ROOT, path).replace(/\\/g, '/');
const lineOf = (text, index) => text.slice(0, index).split('\n').length;

/** Text inside the parentheses that open at `open` (index of '('), string-aware. */
function balanced(text, open) {
  let depth = 0;
  let quote = null;
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    if (quote) {
      if (c === '\\') i++;
      else if (c === quote) quote = null;
      continue;
    }
    if (c === '"' || c === "'") quote = c;
    else if (c === '(') depth++;
    else if (c === ')' && --depth === 0) return text.slice(open + 1, i);
  }
  return text.slice(open + 1);
}

const strings = (text) => [...text.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1].replace(/\\\$/g, '$'));
const field = (body, name) => {
  const m = body.match(new RegExp(`\\b${name}\\s*=\\s*"((?:[^"\\\\]|\\\\.)*)"`));
  return m ? m[1].replace(/\\\$/g, '$') : undefined;
};
const listField = (body, name) => {
  const m = body.match(new RegExp(`\\b${name}\\s*=\\s*listOf\\(`));
  if (!m) return undefined;
  const inner = balanced(body, m.index + m[0].length - 1);
  const quoted = strings(inner);
  if (quoted.length) return quoted;
  return inner.split(',').map((s) => s.trim().replace(/^AccessFlags\./, '')).filter(Boolean);
};

/** The `//` comment block directly above `index`, as human context for a seam. */
function leadingComment(text, index) {
  const before = text.slice(0, index).split('\n');
  before.pop();
  const lines = [];
  while (before.length && /^\s*\/\//.test(before[before.length - 1])) lines.unshift(before.pop().replace(/^\s*\/\/\s?/, ''));
  return lines.join(' ').replace(/\s+/g, ' ').trim() || undefined;
}

function parseFingerprints(file, text) {
  const out = [];
  for (const m of text.matchAll(/object\s+(\w+)\s*:\s*Fingerprint\(/g)) {
    const body = balanced(text, m.index + m[0].length - 1);
    out.push({
      id: m[1],
      file: rel(file),
      line: lineOf(text, m.index),
      definingClass: field(body, 'definingClass'),
      name: field(body, 'name'),
      returnType: field(body, 'returnType'),
      parameters: listField(body, 'parameters'),
      accessFlags: listField(body, 'accessFlags'),
      strings: listField(body, 'strings'),
      hasCustomMatcher: /\bcustom\s*=/.test(body),
      note: leadingComment(text, m.index),
    });
  }
  return out;
}

function parsePatches(file, text) {
  const out = [];
  for (const m of text.matchAll(/val\s+(\w+)\s*=\s*(bytecodePatch|resourcePatch|rawResourcePatch)\(/g)) {
    const args = balanced(text, m.index + m[0].length - 1);
    const descMatch = args.match(/description\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)/);
    // The patch body runs until the next top-level `val` declaration.
    const next = text.slice(m.index + m[0].length).search(/\nval\s+\w+\s*=/);
    const body = next < 0 ? text.slice(m.index) : text.slice(m.index, m.index + m[0].length + next);
    const uses = [];
    for (const u of body.matchAll(/(\w+Fingerprint)\.(method|methodOrNull|classDef|match|originalMethod)\b/g)) {
      const after = body.slice(u.index, u.index + 400);
      const call = after.match(/\.(addInstructions?|addInstructionsWithLabels|replaceInstructions?|removeInstructions?|apply|instructions)\b/)?.[1];
      const smali = after.match(/"((?:[^"\\]|\\.){1,120})"/)?.[1];
      if (!uses.some((x) => x.fingerprint === u[1])) uses.push({ fingerprint: u[1], call, smali: smali?.replace(/\\n/g, '; ') });
    }
    // Fingerprints passed as values (in a list, or to a helper function) are used too.
    for (const u of body.matchAll(/\b(\w+Fingerprint)\b/g)) {
      if (!uses.some((x) => x.fingerprint === u[1])) uses.push({ fingerprint: u[1], call: 'passed to helper/list' });
    }
    out.push({
      id: m[1],
      kind: m[2],
      file: rel(file),
      line: lineOf(text, m.index),
      name: field(args, 'name'),
      description: descMatch ? strings(descMatch[1]).join('').replace(/\s+/g, ' ').trim() : undefined,
      // Opt-in patches declare `default = false` (older style: `use = false`).
      default: /\b(?:default|use)\s*=\s*false\b/.test(args) ? false : undefined,
      uses,
    });
  }
  return out;
}

/** Shared `AppCompatibilities.X = Compatibility(packageName = ..., targets = listOf(AppTarget(..)))` table. */
const sharedCompat = new Map();
for (const root of PATCH_ROOTS) {
  for (const f of walk(join(ROOT, root)).filter((f) => f.endsWith('AppCompatibilities.kt'))) {
    const text = readFileSync(f, 'utf8');
    for (const m of text.matchAll(/val\s+(\w+)\s*=\s*Compatibility\(/g)) {
      const body = balanced(text, m.index + m[0].length - 1);
      sharedCompat.set(m[1], {
        packages: [...body.matchAll(/packageName\s*=\s*"([^"]+)"/g)].map((x) => x[1]),
        targets: [...body.matchAll(/AppTarget\("([^"]+)"/g)].map((x) => x[1]),
      });
    }
  }
}

function parseTargets(texts) {
  const packages = new Set();
  const targets = new Set();
  for (const text of texts) {
    for (const m of text.matchAll(/packageName\s*=\s*"([^"]+)"/g)) packages.add(m[1]);
    for (const m of text.matchAll(/AppTarget\("([^"]+)"/g)) targets.add(m[1]);
    for (const m of text.matchAll(/AppCompatibilities\.(\w+)/g)) {
      const c = sharedCompat.get(m[1]);
      c?.packages.forEach((p) => packages.add(p));
      c?.targets.forEach((t) => targets.add(t));
    }
  }
  return { packages: [...packages], targets: [...targets] };
}

/** Runtime evidence: TAG constants and the literal part of each log message. */
function parseLogs(file, text, lang) {
  const tags = new Set();
  const messages = [];
  if (lang === 'kotlin') {
    for (const m of text.matchAll(/TAG\s*=\s*"([^"]+)"/g)) tags.add(m[1]);
    for (const m of text.matchAll(/Log\.([dewiv])\(\s*TAG\s*,\s*"((?:[^"\\]|\\.)*)"/g)) {
      messages.push({ level: m[1].toUpperCase(), text: m[2].replace(/\$\{[^}]*\}|\$\w+/g, '…'), file: rel(file), line: lineOf(text, m.index) });
    }
  } else {
    for (const m of text.matchAll(/#define\s+LOG_TAG\s+"([^"]+)"/g)) tags.add(m[1]);
    for (const m of text.matchAll(/(?:LOG[DIWE]|__android_log_print\([^,]+,\s*[^,]+)\s*\(?\s*"((?:[^"\\]|\\.)*)"/g)) {
      messages.push({ level: 'N', text: m[1].replace(/%[-+ #0]*\d*(?:\.\d+)?(?:hh|h|ll|l|z|j|t)?[diouxXeEfgGcsp%]/g, '…'), file: rel(file), line: lineOf(text, m.index) });
    }
  }
  return { tags: [...tags], messages };
}

// ---------------------------------------------------------------- build

const apps = {};
for (const root of PATCH_ROOTS) {
  const abs = join(ROOT, root);
  if (!existsSync(abs)) continue;
  for (const app of readdirSync(abs)) {
    if (SKIP.has(app) || !statSync(join(abs, app)).isDirectory()) continue;
    const files = walk(join(abs, app)).filter((f) => f.endsWith('.kt'));
    const texts = files.map((f) => readFileSync(f, 'utf8'));
    const entry = (apps[app] ??= { app, sourceRoots: [], fingerprints: [], patches: [], runtime: { tags: [], messages: [] } });
    entry.sourceRoots.push(rel(join(abs, app)));
    files.forEach((f, i) => {
      entry.fingerprints.push(...parseFingerprints(f, texts[i]));
      entry.patches.push(...parsePatches(f, texts[i]));
    });
    const t = parseTargets(texts);
    entry.packages = [...new Set([...(entry.packages ?? []), ...t.packages])];
    entry.targets = [...new Set([...(entry.targets ?? []), ...t.targets])];
  }
}

const addLogs = (app, logs) => {
  const entry = apps[app];
  if (!entry) return;
  entry.runtime.tags = [...new Set([...entry.runtime.tags, ...logs.tags])];
  entry.runtime.messages.push(...logs.messages);
};
const extAbs = join(ROOT, EXT_ROOT);
if (existsSync(extAbs)) {
  for (const app of readdirSync(extAbs)) {
    for (const f of walk(join(extAbs, app)).filter((f) => /\.(kt|java)$/.test(f))) addLogs(app, parseLogs(f, readFileSync(f, 'utf8'), 'kotlin'));
  }
}
const nativeAbs = join(ROOT, NATIVE_GLOB);
for (const dir of existsSync(nativeAbs) ? readdirSync(nativeAbs) : []) {
  const app = dir.split('-')[0];
  const jni = join(nativeAbs, dir, 'jni');
  if (!apps[app] || !existsSync(jni)) continue;
  for (const f of readdirSync(jni).filter((f) => /\.(cpp|c|h)$/.test(f))) addLogs(app, parseLogs(join(jni, f), readFileSync(join(jni, f), 'utf8'), 'native'));
}

// Cross-link: which fingerprints no patch uses (dead seams), and which patch each fingerprint serves.
for (const entry of Object.values(apps)) {
  for (const fp of entry.fingerprints) fp.usedBy = entry.patches.filter((p) => p.uses.some((u) => u.fingerprint === fp.id)).map((p) => p.name ?? p.id);
  entry.summary = {
    patches: entry.patches.length,
    fingerprints: entry.fingerprints.length,
    unusedFingerprints: entry.fingerprints.filter((f) => f.usedBy.length === 0).map((f) => f.id),
    runtimeMessages: entry.runtime.messages.length,
  };
}

const out = join(ROOT, 'docs', 'seam-map', 'seam-map.generated.json');
mkdirSync(dirname(out), { recursive: true });
const ordered = Object.fromEntries(Object.keys(apps).sort().map((k) => [k, apps[k]]));
writeFileSync(out, JSON.stringify({ generatedFrom: 'scripts/seam-map/extract.mjs — do not edit by hand', apps: ordered }, null, 2) + '\n');
console.log(`wrote ${rel(out)}`);
for (const e of Object.values(ordered)) {
  console.log(`  ${e.app.padEnd(11)} ${String(e.summary.patches).padStart(2)} patches  ${String(e.summary.fingerprints).padStart(2)} fingerprints  ${String(e.summary.runtimeMessages).padStart(3)} runtime log lines  targets: ${e.targets.join(', ') || '-'}${e.summary.unusedFingerprints.length ? `  UNUSED: ${e.summary.unusedFingerprints.join(',')}` : ''}`);
}

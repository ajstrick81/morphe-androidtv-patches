// Seam drift checker: tests every fingerprint of one app against a decompiled APK
// (apktool output: smali/, smali_classes2/, ...) BEFORE a build, and says why each
// seam would miss. For a renamed class it proposes candidates (same method signature,
// or the fingerprint's strings).
//
// Usage: node scripts/seam-map/check.mjs <app> <apktool-dir> [--json] [--deep] [--ref <known-good-apktool-dir>]
// Statuses: OK · SIGNATURE_CHANGED · FLAGS_CHANGED · STRINGS_MISSING · METHOD_MISSING
//           CLASS_MISSING (+ candidates) · UNVERIFIABLE (custom matcher, no class/name)
// Classes are looked up by their `.class` line through an index cached in
// <apktool-dir>/.seam-map-class-index.json (safe on case-insensitive disks; rebuilt when the
// decompile's file count changes). --deep searches every class for rename candidates — use it
// for R8-renamed top-level packages (e.g. LPo/C$c; -> LAp/C$c;), which the default
// package-scoped search can't reach.
import { closeSync, existsSync, openSync, readdirSync, readFileSync, readSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const [app, dir] = process.argv.slice(2);
const asJson = process.argv.includes('--json');
if (!app || !dir) {
  console.error('usage: node scripts/seam-map/check.mjs <app> <apktool-dir> [--json]');
  process.exit(2);
}
const gen = JSON.parse(readFileSync(join(ROOT, 'docs/seam-map/seam-map.generated.json'), 'utf8')).apps[app];
if (!gen) {
  console.error(`unknown app "${app}"; run extract.mjs first`);
  process.exit(2);
}

// ---------------------------------------------------------------- smali index

const smaliRootsOf = (root) => {
  const roots = readdirSync(root).filter((d) => /^smali(_classes\d+)?$/.test(d)).map((d) => join(root, d));
  if (!roots.length) {
    console.error(`no smali*/ folders under ${root}`);
    process.exit(2);
  }
  return roots;
};
const smaliRoots = smaliRootsOf(dir);

// Class descriptor -> smali file, read from each file's own `.class` line — never derived
// from the path. R8 emits names that differ only by case (Tubi 10.36: 10,858 groups such as
// Lsf/c; vs LSf/c;). On a case-insensitive disk (Windows/NTFS, macOS default) apktool writes
// the second one to a renamed folder (sf.1/), and a path lookup for sf/c.smali silently opens
// Sf/c.smali — the wrong class. Built once per decompile and cached beside it.
const INDEX_NAME = '.seam-map-class-index.json';
const INDEX_VERSION = 1;
function readClassLine(file) {
  const fd = openSync(file, 'r');
  try {
    const buf = Buffer.alloc(512);
    const n = readSync(fd, buf, 0, buf.length, 0);
    const m = /^\.class\s+.*?(L[^;\s]+;)/m.exec(buf.toString('utf8', 0, n));
    return m ? m[1] : null;
  } finally {
    closeSync(fd);
  }
}
function loadClassIndex(root, roots) {
  const indexFile = join(root, INDEX_NAME);
  const files = [];
  const walk = (d) => {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const p = join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.smali')) files.push(p);
    }
  };
  roots.forEach(walk);
  if (existsSync(indexFile)) {
    try {
      const cached = JSON.parse(readFileSync(indexFile, 'utf8'));
      if (cached.version === INDEX_VERSION && cached.fileCount === files.length) {
        return new Map(Object.entries(cached.classes).map(([k, rel]) => [k, join(root, rel)]));
      }
    } catch {
      // stale or corrupt: rebuild below
    }
  }
  const classes = new Map();
  for (const f of files) {
    const d = readClassLine(f);
    if (d) classes.set(d, f);
  }
  try {
    const rel = Object.fromEntries([...classes].map(([k, f]) => [k, relative(root, f)]));
    writeFileSync(indexFile, JSON.stringify({ version: INDEX_VERSION, fileCount: files.length, classes: rel }));
  } catch {
    // read-only decompile: fine, just not cached
  }
  return classes;
}
const classIndex = loadClassIndex(dir, smaliRoots);

// --ref <known-good apktool dir>: the decompile the current pins were made for. R8 renames a
// class but keeps its `.source "File.kt"` line, so candidates from the same source file as the
// pinned class there are almost certainly the renamed class (Tubi 10.26: TvWebFragment.kt ->
// LKo/C$c; ranked #3 among look-alike WebViewClients without this, #1 with it).
const refAt = process.argv.indexOf('--ref');
const refDir = refAt > 0 ? process.argv[refAt + 1] : null;
const refIndex = refDir ? loadClassIndex(refDir, smaliRootsOf(refDir)) : null;
const sourceOf = (text) => /^\.source "([^"]*)"/m.exec(text)?.[1] ?? null;
const refSources = new Map();
function refSourceOf(descriptor) {
  if (!refIndex || !descriptor) return null;
  if (!refSources.has(descriptor)) {
    const f = refIndex.get(descriptor);
    refSources.set(descriptor, f ? sourceOf(readFileSync(f, 'utf8')) : null);
  }
  return refSources.get(descriptor);
}
const descriptorByFile = new Map([...classIndex].map(([d, f]) => [f, d]));
const classFile = (descriptor) => classIndex.get(descriptor) ?? null;

/** `.method <flags> name(params)ret` entries of one smali file. */
function methods(text) {
  const out = [];
  for (const m of text.matchAll(/^\.method\s+(.*?)\s*([\w$<>-]+)\((.*?)\)(\S+)\s*$/gm)) {
    out.push({ flags: m[1].split(/\s+/).filter(Boolean), name: m[2], params: m[3], ret: m[4], at: m.index });
  }
  return out;
}
const bodyOf = (text, method) => {
  const end = text.indexOf('.end method', method.at);
  return text.slice(method.at, end < 0 ? undefined : end);
};

const deep = process.argv.includes('--deep');
/**
 * Smali files to search for rename candidates. Default: the original class's package and
 * its parent (SDK renames usually stay inside the SDK's package tree) — cheap even on a
 * slow drive. `--deep` scans every class.
 */
function everySmali(scopeDescriptor) {
  if (deep || !scopeDescriptor) return [...classIndex.values()];
  const parts = scopeDescriptor.replace(/^L/, '').replace(/;$/, '').split('/');
  const pkg = parts.slice(0, -1);
  const scope = pkg.length > 2 ? pkg.slice(0, -1) : pkg; // package + parent
  // Match on the real (case-sensitive) descriptor, not the folder path.
  const prefix = 'L' + scope.join('/') + '/';
  return [...classIndex].filter(([d]) => d.startsWith(prefix)).map(([, f]) => f);
}
const descriptorOf = (file) => descriptorByFile.get(file);

// ---------------------------------------------------------------- matching

const want = (fp) => ({
  params: fp.parameters ? fp.parameters.join('') : null,
  ret: fp.returnType ?? null,
  flags: fp.accessFlags ? fp.accessFlags.map((f) => f.toLowerCase()) : null,
});

function judgeMethod(fp, text, m) {
  const w = want(fp);
  const issues = [];
  if (w.ret && m.ret !== w.ret) issues.push(`returns ${m.ret} (want ${w.ret})`);
  if (w.params !== null && m.params !== w.params) issues.push(`params (${m.params}) (want (${w.params}))`);
  const sig = issues.length ? 'SIGNATURE_CHANGED' : null;
  if (w.flags && !w.flags.every((f) => m.flags.includes(f))) issues.push(`flags [${m.flags.join(' ')}] (want [${w.flags.join(' ')}])`);
  const flags = !sig && issues.length ? 'FLAGS_CHANGED' : null;
  const body = bodyOf(text, m);
  const missing = (fp.strings ?? []).filter((s) => !body.includes(`"${s}"`) && !text.includes(`"${s}"`));
  if (missing.length) issues.push(`strings missing: ${missing.map((s) => JSON.stringify(s)).join(', ')}`);
  const status = sig ?? flags ?? (missing.length ? 'STRINGS_MISSING' : 'OK');
  return { status, issues };
}

// One pass per scope over its classes: methods and const-strings, cached per scope.
const indexes = new Map();
function buildIndex(scopeDescriptor) {
  const key = deep ? '*' : scopeDescriptor ?? '*';
  if (indexes.has(key)) return indexes.get(key);
  const index = [];
  indexes.set(key, index);
  for (const file of everySmali(scopeDescriptor)) {
    const text = readFileSync(file, 'utf8');
    const consts = new Set([...text.matchAll(/const-string(?:\/jumbo)? [vp]\d+, "((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]));
    index.push({ descriptor: descriptorOf(file), source: sourceOf(text), methods: methods(text).map(({ flags, name, params, ret }) => ({ flags, name, params, ret })), consts });
  }
  return index;
}

/**
 * Rename candidates, best first: classes holding all the fingerprint's strings and a
 * method with the same signature; the method name only has to match when it isn't
 * obfuscated-looking (short names are R8 renames, so they are not required).
 */
function candidates(fp, scope = fp.definingClass, { nameOptional = false } = {}) {
  if (!scope && !deep) return ['(no class to scope the search — rerun with --deep)'];
  const w = want(fp);
  const nameRequired = !nameOptional && fp.name && fp.name.length > 3;
  const refSource = refSourceOf(fp.definingClass);
  const scored = [];
  for (const c of buildIndex(scope)) {
    if (fp.strings?.length && !fp.strings.every((s) => c.consts.has(s))) continue;
    for (const m of c.methods) {
      // A hook needs a body: skip interface/abstract declarations of the same signature.
      if (m.flags.includes('abstract')) continue;
      const sigOk = (!w.ret || m.ret === w.ret) && (w.params === null || m.params === w.params);
      if (!sigOk) continue;
      const nameMatch = !fp.name || m.name === fp.name;
      if (nameRequired && !nameMatch && !fp.strings?.length) continue;
      // Compiler-generated names (Kotlin lambdas, synthetic accessors) differ mostly in their numbers.
      // Their stable part is the source name before the first `$` (e.g. `adEventListener_delegate`).
      const base = (s) => s.split('$')[0];
      const shapeMatch = !!fp.name && fp.name.includes('$') && base(m.name) === base(fp.name);
      const sameSource = !!refSource && c.source === refSource;
      const score = (sameSource ? 8 : 0) + (nameMatch ? 4 : 0) + (shapeMatch ? 3 : 0) + (fp.strings?.length ? 2 : 0) + (w.flags && w.flags.every((f) => m.flags.includes(f)) ? 1 : 0);
      scored.push({ score, text: `${c.descriptor}->${m.name}(${m.params})${m.ret}${sameSource ? `  (same .source "${refSource}" as the --ref class)` : ''}` });
    }
  }
  scored.sort((a, b) => b.score - a.score);
  const shown = scored.slice(0, 5).map((s) => s.text);
  if (scored.length > 5) shown.push(`… ${scored.length - 5} more with the same signature`);
  return shown;
}

const results = [];
for (const fp of gen.fingerprints) {
  const r = { fingerprint: fp.id, usedBy: fp.usedBy, target: `${fp.definingClass ?? '*'}->${fp.name ?? '*'}` };
  if (!fp.definingClass) {
    if (!fp.name && !fp.strings?.length) {
      results.push({ ...r, status: 'UNVERIFIABLE', issues: ['custom matcher only; verify with a patch run'] });
      continue;
    }
    const c = candidates(fp);
    results.push({ ...r, status: c.length ? 'OK' : 'METHOD_MISSING', issues: c.length ? [`found by name/strings: ${c[0]}`] : ['no class has a matching method'], candidates: c.slice(1) });
    continue;
  }
  const file = classFile(fp.definingClass);
  if (!file) {
    results.push({ ...r, status: 'CLASS_MISSING', issues: ['class not found (renamed/obfuscated or removed)'], candidates: candidates(fp) });
    continue;
  }
  const text = readFileSync(file, 'utf8');
  const all = methods(text);
  // R8 also renames members (Tubi 10.36: ImagePauseAds.l -> m). Same-class methods with the
  // fingerprint's exact signature are the first place to look when the name is gone or moved.
  const w = want(fp);
  const sameClass = all
    .filter((m) => m.name !== fp.name && !m.flags.includes('abstract'))
    .filter((m) => (!w.ret || m.ret === w.ret) && w.params !== null && m.params === w.params)
    .map((m) => `${fp.definingClass}->${m.name}(${m.params})${m.ret}  (same class, renamed member?)`);
  const ms = all.filter((m) => !fp.name || m.name === fp.name);
  if (!ms.length) {
    results.push({ ...r, status: 'METHOD_MISSING', issues: [`no method ${fp.name} in class`], candidates: [...sameClass, ...candidates(fp, fp.definingClass, { nameOptional: true })] });
    continue;
  }
  const judged = ms.map((m) => judgeMethod(fp, text, m));
  const best = judged.find((j) => j.status === 'OK') ?? judged[0];
  results.push({ ...r, status: best.status, issues: best.issues, candidates: best.status === 'SIGNATURE_CHANGED' ? sameClass : undefined, note: fp.hasCustomMatcher ? 'also has a custom matcher (not evaluated here)' : undefined });
}

if (asJson) {
  console.log(JSON.stringify({ app, dir, results }, null, 2));
} else {
  const counts = results.reduce((a, x) => ((a[x.status] = (a[x.status] ?? 0) + 1), a), {});
  console.log(`${app} vs ${dir}  (targets: ${gen.targets.join(', ')})`);
  for (const x of results) {
    console.log(`  ${x.status.padEnd(18)} ${x.fingerprint}  [${x.usedBy.join(', ') || 'unused'}]`);
    for (const i of x.issues ?? []) console.log(`      ${i}`);
    if (x.note) console.log(`      note: ${x.note}`);
    for (const c of x.candidates ?? []) console.log(`      candidate: ${c}`);
  }
  console.log(`SUMMARY ${Object.entries(counts).map(([k, v]) => `${k}=${v}`).join(' ')}`);
  process.exitCode = results.some((x) => !['OK', 'UNVERIFIABLE'].includes(x.status)) ? 1 : 0;
}

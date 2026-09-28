// Renders docs/SEAM_MAP.md from the generated code facts + the hand-curated overlay.
// Usage: node scripts/seam-map/extract.mjs && node scripts/seam-map/render.mjs
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const gen = JSON.parse(readFileSync(join(ROOT, 'docs/seam-map/seam-map.generated.json'), 'utf8')).apps;
const overlay = JSON.parse(readFileSync(join(ROOT, 'docs/seam-map/overlay.json'), 'utf8'));

const esc = (s) => String(s ?? '').replace(/\|/g, '\\|').replace(/\n/g, ' ');
const code = (s) => (s ? `\`${esc(s)}\`` : '—');
const short = (cls) => (cls ? cls.replace(/^L/, '').replace(/;$/, '').split('/').slice(-2).join('/') : '(custom matcher)');

const out = [];
out.push('# Seam map');
out.push('');
out.push('How each app delivers ads, where our patches cut in, and what proves it worked. Generated — do not edit by hand.');
out.push('');
out.push('- **Code facts** (packages, targets, patches, fingerprints, runtime log lines) come from the sources via `node scripts/seam-map/extract.mjs`.');
out.push('- **Mechanism notes** (engine, delivery, seam, device signature, drift) live in `docs/seam-map/overlay.json`.');
out.push('- **Drift check** a new APK before building: `node scripts/seam-map/check.mjs <app> <apktool-output-dir>`. After an R8 rename, add `--deep --ref <last-good-apktool-dir>` to rank the renamed class first (it keeps its `.source` file name).');
out.push('- Rebuild this page: `node scripts/seam-map/extract.mjs && node scripts/seam-map/render.mjs`.');
out.push('');
out.push('**The four layers.** A seam is only understood when all four line up: *delivery* (how ads reach the player) → *code seam* (the fingerprint we match) → *patch evidence* (the log line our code prints when it acts) → *device signature* (what logcat shows when an ad actually plays). After an app update, patch evidence going quiet while the device signature appears is the fastest drift alarm.');
out.push('');

// Engine overview
out.push('## Engines');
out.push('');
out.push('| Engine | Apps | How ads arrive | Seams that port within the family |');
out.push('|---|---|---|---|');
for (const [key, engine] of Object.entries(overlay.engines)) {
  const members = Object.entries(overlay.apps).filter(([, a]) => a.engine === key).map(([k]) => k);
  out.push(`| **${esc(engine.label)}** | ${members.join(', ')} | ${esc(engine.shape)} | ${esc(engine.portable_seams)} |`);
}
out.push('');

// Coverage matrix
out.push('## Coverage');
out.push('');
out.push('| App | Engine | Targets | Patches | Fingerprints | Patch evidence logged | Device signature known |');
out.push('|---|---|---|---:|---:|---|---|');
for (const [key, g] of Object.entries(gen)) {
  const o = overlay.apps[key] ?? {};
  out.push(`| [${key}](#${key}) | ${esc(overlay.engines[o.engine]?.label ?? '?')} | ${esc(g.targets.join(', '))} | ${g.summary.patches} | ${g.summary.fingerprints} | ${g.summary.runtimeMessages ? `yes (${g.summary.runtimeMessages} lines)` : '**no**'} | ${o.device_signature ? 'yes' : '**no**'} |`);
}
out.push('');

// Per app
for (const [key, g] of Object.entries(gen)) {
  const o = overlay.apps[key] ?? {};
  out.push(`## ${key}`);
  out.push('');
  out.push(`**Package** ${g.packages.map(code).join(', ') || '—'} · **Targets** ${g.targets.map(code).join(', ') || '—'} · **Engine** ${esc(overlay.engines[o.engine]?.label ?? 'unclassified')}`);
  out.push('');
  const rows = [
    ['Runtime', o.runtime],
    ['Delivery', o.delivery],
    ['Code seam', o.seam],
    ['Patch action', o.action],
    ['Patch evidence', o.patch_evidence],
    ['Device signature', o.device_signature],
    ['Drift history', o.drift],
  ];
  out.push('| Layer | |');
  out.push('|---|---|');
  for (const [label, value] of rows) out.push(`| ${label} | ${value ? esc(value) : '_unknown_'} |`);
  out.push('');
  if (o.gaps?.length) {
    out.push('**Gaps:** ' + o.gaps.map(esc).join(' · '));
    out.push('');
  }
  out.push('<details><summary>Patches and fingerprints (generated)</summary>');
  out.push('');
  out.push('| Patch | Fingerprint | Class / method | Returns | Strings | Action |');
  out.push('|---|---|---|---|---|---|');
  for (const p of g.patches) {
    const uses = p.uses.length ? p.uses : [{ fingerprint: null }];
    for (const u of uses) {
      const fp = g.fingerprints.find((f) => f.id === u.fingerprint);
      out.push(
        `| ${esc(p.name ?? p.id)}${p.default === false ? ' _(opt-in)_' : ''} | ${u.fingerprint ? code(u.fingerprint) : '—'} | ${fp ? `${code(short(fp.definingClass))}.${code(fp.name)}` : '—'} | ${fp ? code(fp.returnType) : '—'} | ${fp?.strings?.map(code).join(' ') ?? ''} | ${esc(u.call ?? '')}${u.smali ? ` ${code(u.smali)}` : ''} |`,
      );
    }
  }
  out.push('');
  if (g.runtime.messages.length) {
    out.push(`Runtime evidence (tags ${g.runtime.tags.map(code).join(', ') || '—'}; first 12 of ${g.runtime.messages.length}):`);
    out.push('');
    for (const m of g.runtime.messages.slice(0, 12)) out.push(`- ${m.level} ${code(m.text.slice(0, 110))} — ${m.file.split('/').pop()}:${m.line}`);
    out.push('');
  }
  out.push('</details>');
  out.push('');
}

writeFileSync(join(ROOT, 'docs/SEAM_MAP.md'), out.join('\n'));
console.log(`wrote docs/SEAM_MAP.md (${out.length} lines, ${Object.keys(gen).length} apps)`);

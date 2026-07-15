#!/usr/bin/env node
// Validates the AdGuard Home filter lists in dns/.
//
// AdGuard Home drops malformed rules at load time without reporting them, so a
// typo silently disables a block rather than failing loudly. This checks the
// grammar documented at
// https://adguard-dns.io/kb/general/dns-filtering-syntax/
//
// Deliberately dependency-free: @adguard/aglint cannot be used here, as its
// compatibility tables have no AdGuard Home/DNS platform and flag every
// `$dnsrewrite` as a non-existent modifier. See docs/ADGUARD_DNS_SYNTAX.md.
//
// Usage: node tools/lint-dns-rules.mjs [files...]   (defaults to dns/*.txt)

import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

// Modifier -> which engine implements it. Our lists target AdGuard Home.
const MODIFIERS = {
  client: 'both',
  denyallow: 'both',
  dnstype: 'both',
  dnsrewrite: 'both',
  important: 'both',
  badfilter: 'both',
  ctag: 'home', // AdGuard Home only
  respgeo: 'dns', // AdGuard DNS (hosted) only — NOT valid in our lists
};

const IPV4 = /^\d{1,3}(\.\d{1,3}){3}$/;
const IPV6 = /^[0-9a-f:]+:[0-9a-f:]*$/i;

/** Split a rule into its pattern and raw modifier string. */
function splitRule(rule) {
  const body = rule.startsWith('@@') ? rule.slice(2) : rule;

  // Regex rules: /regexp/$modifiers — the regexp itself may contain '$'.
  if (body.startsWith('/')) {
    const m = body.match(/^\/(.*)\/(?:\$(.*))?$/);
    if (!m) return { error: 'unterminated regex rule (expected /regexp/)' };
    return { regex: m[1], mods: m[2] ?? '' };
  }

  const i = body.indexOf('$');
  return i === -1
    ? { pattern: body, mods: '' }
    : { pattern: body.slice(0, i), mods: body.slice(i + 1) };
}

function lintLine(rule) {
  const problems = [];
  const { regex, mods, error } = splitRule(rule);

  if (error) return [error];

  if (regex !== undefined) {
    try {
      new RegExp(regex);
    } catch (e) {
      problems.push(`invalid regex: ${e.message}`);
    }
  }

  if (!mods) return problems;

  // The docs: "Modifiers must be located at the end of the rule after the `$`
  // character and be separated by commas." A '$' here means someone wrote
  // `$important$dnsrewrite=...` instead of `$important,dnsrewrite=...`.
  if (mods.includes('$')) {
    problems.push(
      `modifiers must be separated by commas, not '$' — found "$${mods}"`,
    );
    return problems;
  }

  for (const part of mods.split(',')) {
    const raw = part.trim();
    if (!raw) {
      problems.push('empty modifier (stray comma)');
      continue;
    }
    const name = raw.replace(/^~/, '').split('=')[0];
    const engine = MODIFIERS[name];

    if (!engine) {
      problems.push(`unknown modifier: '${name}'`);
    } else if (engine === 'dns') {
      problems.push(
        `'${name}' is AdGuard DNS (hosted) only and is not supported by ` +
          `AdGuard Home — it will be ignored in this list`,
      );
    }
  }
  return problems;
}

function lintFile(path) {
  const found = [];
  const lines = readFileSync(path, 'utf8').split(/\r?\n/);

  lines.forEach((line, idx) => {
    const text = line.trim();
    if (!text || text.startsWith('!') || text.startsWith('#')) return;

    // /etc/hosts-style: "IP hostname [aliases]" — no modifier grammar.
    const [first] = text.split(/\s+/);
    if (/\s/.test(text) && (IPV4.test(first) || IPV6.test(first))) return;

    for (const problem of lintLine(text)) {
      found.push({ line: idx + 1, problem, text });
    }
  });
  return found;
}

const files = process.argv.slice(2).length
  ? process.argv.slice(2)
  : readdirSync('dns')
      .filter((f) => f.endsWith('.txt'))
      .map((f) => join('dns', f));

let total = 0;
for (const file of files) {
  const problems = lintFile(file);
  if (!problems.length) continue;
  total += problems.length;
  console.error(`\n${file}`);
  for (const { line, problem, text } of problems) {
    console.error(`  ${String(line).padStart(4)}:  ${problem}`);
    console.error(`        ${text}`);
  }
}

if (total) {
  console.error(`\n✗ ${total} problem(s) in ${files.length} file(s).`);
  process.exit(1);
}
console.log(`✓ ${files.length} file(s) OK.`);

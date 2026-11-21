const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('patches/src/main/resources/netflix/native/killads.js', 'utf8');
const start = source.indexOf('// SNAPSHOT CORE BEGIN');
const end = source.indexOf('// SNAPSHOT CORE END');
const context = vm.createContext({});
if (start >= 0 && end > start) vm.runInContext(source.slice(start, end), context);

class Pointer {
  constructor(n) { this.n = n instanceof Pointer ? n.n : Number(n); }
  add(n) { return new Pointer(this.n + Number(n)); }
  sub(p) { return new Pointer(this.n - p.n); }
  compare(p) { return Math.sign(this.n - p.n); }
  toInt32() { return this.n | 0; }
  toString() { return '0x' + this.n.toString(16); }
  and(p) { return new Pointer(this.n & p.n); }
}

function fixture(regions, { shortRead = Infinity, rejectSpanningHole = false, pageSize = 8 } = {}) {
  const scratch = { base: 0x80000, bytes: Buffer.alloc(31) };
  const live = regions.map(([base, bytes]) => ({ base, bytes: Buffer.from(bytes) }));
  let allocations = 0;
  let calls = 0;
  const io = {
    blockSize: 16, maxPattern: 16, pageSize, pageMask: new Pointer(~(pageSize - 1)),
    allocate(n) { allocations++; assert.equal(n, 31); return new Pointer(scratch.base); },
    read(dst, src, length) {
      calls++;
      if (rejectSpanningHole && Array.from({ length }, (_, i) => src.n + i).some(address => ![...live, scratch].some(r => address >= r.base && address < r.base + r.bytes.length))) return -1;
      let n = 0;
      while (n < length && n < shortRead) {
        const region = [...live, scratch].find(r => src.n + n >= r.base && src.n + n < r.base + r.bytes.length);
        if (!region) break;
        scratch.bytes[dst.n + n - scratch.base] = region.bytes[src.n + n - region.base];
        n++;
      }
      return n || -1;
    },
    scan(base, length, pattern) {
      assert.equal(base.n, scratch.base, 'native scans must use owned memory only');
      const parts = pattern.split(/\s+/);
      const found = [];
      for (let i = 0; i <= length - parts.length; i++) {
        if (parts.every((p, j) => [...p].every((hex, k) => hex === '?' || parseInt(hex, 16) === ((scratch.bytes[i + j] >> (k ? 0 : 4)) & 15)))) {
          found.push({ address: new Pointer(base.n + i), size: parts.length });
        }
      }
      return found;
    },
  };
  assert.equal(typeof context.createSnapshotScanner, 'function', 'snapshot scanner is not implemented');
  const scan = context.createSnapshotScanner(io);
  return { scan: (base, size, pattern) => Array.from(scan(new Pointer(base), size, pattern), h => [h.address.n, h.size]),
    allocations: () => allocations, calls: () => calls };
}

test('finds matches and returns original addresses, never copied addresses', () => {
  const f = fixture([[0x1000, 'xxABxxAB']]);
  assert.deepEqual(f.scan(0x1000, 8, '41 42'), [[0x1002, 2], [0x1006, 2]]);
});
test('finds a pattern spanning a copy boundary exactly once', () => {
  const f = fixture([[0x1000, '..............ABCDE..............ABCDE']]);
  assert.deepEqual(f.scan(0x1000, 38, '41 42 43 44 45'), [[0x100e, 5], [0x1021, 5]]);
});
test('continues after unreadable pages and retains the readable prefix', () => {
  const f = fixture([[0x1000, 'AB......'], [0x1010, '..AB....']]);
  assert.deepEqual(f.scan(0x1000, 24, '41 42'), [[0x1000, 2], [0x1012, 2]]);
});
test('accumulates short successful kernel reads before searching', () => {
  const f = fixture([[0x1000, '.....ABCDEFG....']], { shortRead: 3 });
  assert.deepEqual(f.scan(0x1000, 16, '41 42 43 44 45 46 47'), [[0x1005, 7]]);
});
test('supports wildcard matching and the exact end of a range', () => {
  const f = fixture([[0x1000, [0x12, 0xab, 0x34, 0xab, 0x56]]]);
  assert.deepEqual(f.scan(0x1000, 5, 'ab ??'), [[0x1001, 2], [0x1003, 2]]);
});
test('never searches scratch storage even when it appears in a target mapping', () => {
  const f = fixture([[0x7fff8, 'AB......'], [0x8001f, 'AB......']]);
  f.scan(0x7fff8, 8, '41 42');
  assert.deepEqual(f.scan(0x7fff8, 47, '41 42'), [[0x7fff8, 2], [0x8001f, 2]]);
});
test('reuses one bounded allocation across scans and patterns', () => {
  const f = fixture([[0x1000, 'ABABABABABABABAB']]);
  for (let i = 0; i < 50; i++) f.scan(0x1000, 16, '41 42');
  assert.equal(f.allocations(), 1);
});
test('unreadable input terminates and invalid patterns do not read memory', () => {
  const f = fixture([]);
  assert.deepEqual(f.scan(0x1001, 31, '41 42'), []);
  assert.ok(f.calls() <= 8, 'at most a bulk attempt and page retry per inaccessible page');
  const before = f.calls();
  assert.throws(() => f.scan(0x1000, 16, ''), /pattern/i);
  assert.equal(f.calls(), before);
});
test('retains readable pages when a kernel rejects the whole cross-hole read', () => {
  const f = fixture([[0x1000, 'AB......'], [0x1010, '..AB....']], { rejectSpanningHole: true });
  assert.deepEqual(f.scan(0x1000, 24, '41 42'), [[0x1000, 2], [0x1012, 2]]);
});
test('an unavailable kernel reader is initialized once and reports one failure', () => {
  assert.equal(typeof context.createLazySnapshotScanner, 'function');
  let initializations = 0;
  const messages = [];
  const scan = context.createLazySnapshotScanner(() => { initializations++; throw new Error('kernel reader unavailable'); }, message => messages.push(message));
  for (let i = 0; i < 100; i++) assert.deepEqual(Array.from(scan(new Pointer(0x1000), 8, '41 42')), []);
  assert.equal(initializations, 1);
  assert.equal(messages.length, 1);
  assert.match(messages[0], /kernel reader unavailable/);
});
test('a large module can be searched without allocating a module-sized buffer', () => {
  const f = fixture([[0x1000, 'AB......']], { pageSize: 64 * 1024 * 1024 });
  assert.deepEqual(f.scan(0x1000, 128 * 1024 * 1024 + 1, '41 42'), [[0x1000, 2]]);
  assert.equal(f.allocations(), 1);
  assert.ok(f.calls() < 20);
});

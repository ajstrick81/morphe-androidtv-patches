# find_offsets.py — Ghidra headless post-script.
# Locate zlib inflate() + the OpenSSL SSL_read wrapper in the stripped
# libignite.so by cross-referencing their unique .rodata strings (Ghidra
# resolves the PC-relative refs that a byte-scan cannot), and print each
# enclosing function's image-relative offset + prologue bytes for offsets.h.
#
#@category PrimeVideo
import jarray

fm     = currentProgram.getFunctionManager()
mem    = currentProgram.getMemory()
refmgr = currentProgram.getReferenceManager()
base   = currentProgram.getImageBase()

def find_str(text):
    b = jarray.array([ord(c) for c in text], 'b')
    return mem.findBytes(currentProgram.getMinAddress(), b, None, True, monitor)

def prologue_hex(ep, n):
    b = jarray.zeros(n, 'b')
    try:
        mem.getBytes(ep, b)
        return " ".join("%02x" % (x & 0xff) for x in b)
    except:
        return "?"

anchors = [
  ("inflate", ["incorrect header check",
               "invalid stored block lengths",
               "unknown compression method",
               "invalid window size"]),
  ("ssl_read_wrapper", ["OpenSSL SSL_read: %s, errno %d"]),
  ("ssl_write_wrapper", ["OpenSSL SSL_write: %s, errno %d"]),
]

print("=== GHIDRA OFFSET RECOVERY ===")
print("IMAGE_BASE = %s" % base)

for label, strs in anchors:
    print("\n==== %s ====" % label)
    funcs = {}  # entryOffset(int) -> [func, set(strings), refcount]
    for s in strs:
        a = find_str(s)
        if a is None:
            print("  [not found] '%s'" % s); continue
        n = 0
        for r in refmgr.getReferencesTo(a):
            frm = r.getFromAddress()
            f = fm.getFunctionContaining(frm)
            n += 1
            if f:
                key = f.getEntryPoint().getOffset()
                funcs.setdefault(key, [f, set(), 0])
                funcs[key][1].add(s)
            else:
                print("    ref @ %s has NO containing function" % frm)
        print("  '%s' @ %s : %d ref(s)" % (s, a, n))
    for key in sorted(funcs):
        f, ss, _ = funcs[key]
        off = key - base.getOffset()
        print("  >>> FUNC %s  entry=%s  offset=0x%x  (matched %d anchor string(s))"
              % (f.getName(), f.getEntryPoint(), off, len(ss)))
        print("      prologue(32): %s" % prologue_hex(f.getEntryPoint(), 32))

print("\n==== auto-named library candidates (FidDb) ====")
it = fm.getFunctions(True)
while it.hasNext():
    f = it.next()
    nm = f.getName().lower()
    if ("inflate" in nm or "ssl_read" in nm or "ssl_write" in nm
            or "uncompress" in nm):
        off = f.getEntryPoint().getOffset() - base.getOffset()
        print("  %s  entry=%s  offset=0x%x" % (f.getName(), f.getEntryPoint(), off))
print("\n=== DONE ===")

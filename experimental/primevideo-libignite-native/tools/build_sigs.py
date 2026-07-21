#!/usr/bin/env python3
# Build unique, minimally-masked signatures for inflate + SSL_read wrapper.
import struct
SO = r"C:\Users\Adam\ghidra_work\libignite.so"
B = open(SO, "rb").read()
# .text file range
TEXT_OFF, TEXT_END = 0x79ba80, 0x79ba80 + 0x72358c

def count(sig_bytes, mask):
    n = 0; positions = []
    i = TEXT_OFF
    L = len(sig_bytes)
    while i < TEXT_END - L:
        ok = True
        for j in range(L):
            if mask[j] == 'x' and B[i+j] != sig_bytes[j]:
                ok = False; break
        if ok:
            n += 1; positions.append(i)
            if n > 3: break
        i += 2   # Thumb 2-byte alignment
    return n, positions

FUNCS = {
    "inflate":          0xd32f7a,
    "ssl_read_wrapper": 0xc4fe3c,
}

for name, ent in FUNCS.items():
    print("=== %s @ 0x%x ===" % (name, ent))
    raw = B[ent:ent+32]
    # try growing exact-match lengths until unique
    chosen = None
    for L in (12, 16, 20, 24, 28, 32):
        mask = 'x' * L
        n, pos = count(raw[:L], mask)
        print("  len=%2d exact matches=%d" % (L, n))
        if n == 1 and chosen is None:
            chosen = L
    L = chosen or 32
    sig = raw[:L]
    cbytes = "".join("\\x%02x" % b for b in sig)
    print("  -> unique at len=%d" % L)
    print("     SIG  = \"%s\"" % cbytes)
    print("     MASK = \"%s\"" % ("x"*L))
    print("     bytes= %s" % " ".join("%02x"%b for b in sig))
    print()

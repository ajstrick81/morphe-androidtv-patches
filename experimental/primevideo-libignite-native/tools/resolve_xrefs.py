#!/usr/bin/env python3
# Resolve ARM Thumb PIC string references in libignite.so that no off-the-shelf
# tool would resolve here (Ghidra OSGi broke; objdump/r2 didn't annotate them).
#
# Pattern:  ldr rX, [pc, #k]   ; @ 0xPOOL  (objdump gives the pool address)
#           ... add rX, pc     ; rX = (add_site + 4) + *(u32*)POOL  = target
# We read the pool word W from the .so and compute the target, then match against
# the known zlib/OpenSSL anchor-string vaddrs to locate inflate() / SSL_read wrap.
import re, struct

DISASM = r"C:\Users\Adam\ghidra_work\disasm.txt"
SO     = r"C:\Users\Adam\ghidra_work\libignite.so"
B = open(SO, "rb").read()

# .text/.rodata have vaddr==file offset in this .so; pool words live in .text.
def u32(off):
    return struct.unpack_from("<I", B, off)[0]

TARGETS = {
    0x12c891: "inflate:incorrect header check",
    0x000ee9c2: "inflate:invalid stored block lengths",
    0x00139d78: "inflate:unknown compression method",
    0x0011f5d0: "inflate:invalid window size",
    0x000fa90a: "ssl_read_wrapper",
    0x000e1673: "ssl_write_wrapper",
}

# line forms:
#   79bb06: 484f       ldr r0, [pc, #0x13c]    @ 0x79bc44 <...>
#   79bb0a: 4479       add r1, pc
line_re  = re.compile(r"^\s*([0-9a-f]+):\s+((?:[0-9a-f]{2}\s?)+?)\s+(\S+)\s*(.*)$")
ldr_re   = re.compile(r"^ldr(?:\.w)?$")
poolcmt  = re.compile(r"@\s*0x([0-9a-f]+)")
addpc_re = re.compile(r"^add$")

# reg -> (pool_word W, ldr_addr) from the most recent ldr rX,[pc..]
reg_w = {}
hits = []   # (add_addr, target, label)

with open(DISASM, "r", errors="replace") as f:
    for ln in f:
        m = line_re.match(ln)
        if not m:
            continue
        addr = int(m.group(1), 16)
        mn   = m.group(3)
        ops  = m.group(4)
        if ldr_re.match(mn) and "pc" in ops and "[pc" in ops:
            reg = ops.split(",")[0].strip()
            pc = poolcmt.search(ops)
            if pc:
                pool = int(pc.group(1), 16)
                try:
                    reg_w[reg] = u32(pool)
                except Exception:
                    reg_w.pop(reg, None)
        elif addpc_re.match(mn) and re.search(r",\s*pc$", ops):
            reg = ops.split(",")[0].strip()
            if reg in reg_w:
                target = (addr + 4 + reg_w[reg]) & 0xffffffff
                if target in TARGETS:
                    hits.append((addr, target, TARGETS[target]))
        else:
            # any write to a tracked reg between ldr and add invalidates it
            dst = ops.split(",")[0].strip()
            if dst in reg_w and mn not in ("ldr",):
                reg_w.pop(dst, None)

print("=== resolved string references ===")
for addr, tgt, label in hits:
    print("  ref @ 0x%08x  ->  0x%08x  [%s]" % (addr, tgt, label))
print("total hits: %d" % len(hits))

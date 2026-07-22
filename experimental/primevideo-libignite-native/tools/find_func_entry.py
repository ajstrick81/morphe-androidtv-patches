#!/usr/bin/env python3
# Given interior reference addresses, find the enclosing function's entry by
# scanning the disassembly for Thumb prologues that follow a function terminator.
import re, struct

DISASM = r"C:\Users\Adam\ghidra_work\disasm.txt"
SO     = r"C:\Users\Adam\ghidra_work\libignite.so"
B = open(SO, "rb").read()

REFS = {
    0x00d344c6: "inflate (incorrect header check)",
    0x00d5e5b6: "inflate (invalid window size)",
    0x00c4ffac: "SSL_read wrapper",
}

line_re = re.compile(r"^\s*([0-9a-f]+):\s+((?:[0-9a-f]{2}\s?)+?)\s+(\S+)\s*(.*)$")

# Build ordered instruction list: (addr, mnemonic, ops, rawbytes_str)
insns = []
with open(DISASM, "r", errors="replace") as f:
    for ln in f:
        m = line_re.match(ln)
        if not m:
            continue
        insns.append((int(m.group(1), 16), m.group(3), m.group(4),
                      m.group(2).replace(" ", "")))

# A function terminator: unconditional return / tail branch.
def is_terminator(mn, ops):
    if mn == "bx" and ops.strip() == "lr":        return True
    if mn in ("pop", "pop.w") and "pc" in ops:    return True
    if mn in ("b", "b.w") and ops.startswith("0x"): return True  # tail call/branch
    if mn == "bx" and ops.strip() == "pc":        return True
    return False

# A prologue: pushes lr (saves return addr) — b5xx (push {..lr}) / e92d....(push.w {..lr}).
def is_prologue(mn, ops, raw):
    if mn in ("push", "push.w") and "lr" in ops:  return True
    return False

# Mark function starts: a prologue whose previous instruction is a terminator
# (or the very first instruction / a data gap).
starts = []
prev_term = True   # start of listing counts as a boundary
for i, (addr, mn, ops, raw) in enumerate(insns):
    if is_prologue(mn, ops, raw) and prev_term:
        starts.append(addr)
    prev_term = is_terminator(mn, ops)
starts.sort()

import bisect
def enclosing(ref):
    j = bisect.bisect_right(starts, ref) - 1
    return starts[j] if j >= 0 else None

# index addr->position for prologue byte fetch
addr_pos = {a: k for k, (a, _, _, _) in enumerate(insns)}

for ref in sorted(REFS):
    ent = enclosing(ref)
    label = REFS[ref]
    if ent is None:
        print("ref 0x%08x [%s]: no enclosing function found" % (ref, label)); continue
    off = ent  # vaddr == file offset base; module-relative offset == vaddr here (image base 0)
    prologue = " ".join("%02x" % b for b in B[ent:ent+32])
    print("=== %s ===" % label)
    print("  ref        @ 0x%08x" % ref)
    print("  FUNC ENTRY @ 0x%08x   (file offset / image-relative 0x%x)" % (ent, off))
    print("  thumb bit  : entry is Thumb (call addr = 0x%x | 1)" % ent)
    print("  prologue32 : %s" % prologue)
    # show first few disasm lines of the function for sanity
    k = addr_pos.get(ent)
    if k is not None:
        print("  disasm:")
        for a, mn, ops, raw in insns[k:k+6]:
            print("    0x%08x  %-8s %s" % (a, mn, ops))
    print()

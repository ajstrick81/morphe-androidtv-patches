import struct, re
SO='lib/armeabi-v7a/libnetflix.so'
data=open(SO,'rb').read()

# --- parse program headers to map vaddr<->fileoff ---
e_phoff=struct.unpack_from('<I',data,0x1c)[0]
e_phentsize=struct.unpack_from('<H',data,0x2a)[0]
e_phnum=struct.unpack_from('<H',data,0x2c)[0]
segs=[]
for i in range(e_phnum):
    off=e_phoff+i*e_phentsize
    p_type,p_offset,p_vaddr,p_paddr,p_filesz,p_memsz,p_flags,p_align=struct.unpack_from('<IIIIIIII',data,off)
    if p_type==1:  # PT_LOAD
        segs.append((p_vaddr,p_offset,p_filesz,p_flags))
def v2o(v):
    for va,fo,fs,fl in segs:
        if va<=v<va+fs: return fo+(v-va)
    return None
def o2v(o):
    for va,fo,fs,fl in segs:
        if fo<=o<fo+fs: return va+(o-fo)
    return None
def rd32(v):
    o=v2o(v)
    if o is None or o+4>len(data): return None
    return struct.unpack_from('<I',data,o)[0]
def rdstr(v,maxlen=64):
    o=v2o(v)
    if o is None: return None
    end=data.find(b'\x00',o,o+maxlen)
    if end<0: end=o+maxlen
    return data[o:end].decode('latin1','replace')

# --- find all vaddrs of the exact class string "Gibbon2Bridge\0" ---
needle=b'Gibbon2Bridge\x00'
class_vaddrs=set()
i=0
while True:
    j=data.find(needle,i)
    if j<0: break
    v=o2v(j)
    if v is not None: class_vaddrs.add(v)
    i=j+1
print('Gibbon2Bridge string copies:',len(class_vaddrs))

# --- scan data (RW) segments for a word == one of the class string vaddrs (descriptor.class field) ---
# descriptor layout: name@+0, class@+4, nargs@+8, argtable@+0xc, ... callback@+0x18
found={}
for va,fo,fs,fl in segs:
    # scan whole file range of this segment for pointers
    base=fo
    for o in range(fo, fo+fs-4, 4):
        w=struct.unpack_from('<I',data,o)[0]
        if w in class_vaddrs:
            desc_v=o2v(o)-4   # this word is class field at desc+4
            if desc_v is None: continue
            name_ptr=rd32(desc_v+0)
            nargs=rd32(desc_v+8)
            cb=rd32(desc_v+0x18)
            if name_ptr is None: continue
            nm=rdstr(name_ptr)
            if nm is None or not re.match(r'^[A-Za-z_][A-Za-z0-9_]{0,40}$', nm): continue
            if nargs is None or nargs>16: continue
            # callback should be an exec-seg vaddr (odd/thumb tolerated)
            if cb is None: continue
            found[desc_v]=(nm,nargs,cb)
print('candidate Gibbon2Bridge method descriptors:',len(found))
# print sorted by name, dedup by (name,cb)
seen=set()
rows=[]
for desc_v,(nm,nargs,cb) in found.items():
    key=(nm,cb)
    if key in seen: continue
    seen.add(key)
    rows.append((nm,nargs,cb,desc_v))
rows.sort()
for nm,nargs,cb,desc_v in rows:
    print(f'{nm:28s} nargs={nargs} cb=0x{cb&~1:x} desc=0x{desc_v:x}')

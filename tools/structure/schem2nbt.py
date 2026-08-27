# -*- coding: utf-8 -*-
"""MCEdit .schematic -> Minecraft 1.21.1 structure .nbt.

Crops to the occupied bounding box, optionally strips the terrain pad the build
was captured with, and flattens pre-1.13 numeric ids to modern blockstates.
"""
import sys, os, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nbt import read
from nbtwrite import write
from flatten import convert

# Blocks that are captured terrain rather than build, stripped when asked.
TERRAIN = {2,3,80,9,13,31,37,38,175,78,79,12,110}

def load(path):
    d = read(path)
    return d['Width'], d['Height'], d['Length'], d['Blocks'], d['Data']

def convert_file(src, dst, strip_terrain=True, verbose=True):
    W,H,L,B,D = load(src)
    def at(x,y,z):
        i = (y*L + z)*W + x
        return B[i]&0xff, D[i]&0x0f

    # occupied bounds, ignoring terrain when stripping
    xs,ys,zs = [],[],[]
    for y in range(H):
        for z in range(L):
            for x in range(W):
                b,_ = at(x,y,z)
                if b == 0: continue
                if strip_terrain and b in TERRAIN: continue
                xs.append(x); ys.append(y); zs.append(z)
    if not xs:
        return None
    x0,x1 = min(xs),max(xs); y0,y1 = min(ys),max(ys); z0,z1 = min(zs),max(zs)
    sx,sy,sz = x1-x0+1, y1-y0+1, z1-z0+1

    palette, pidx, blocks = [], {}, []
    unmapped = collections.Counter()
    for y in range(y0,y1+1):
        for z in range(z0,z1+1):
            for x in range(x0,x1+1):
                b,dv = at(x,y,z)
                if b == 0: continue
                if strip_terrain and b in TERRAIN: continue
                r = convert(b,dv)
                if r is None:
                    unmapped[(b,dv)] += 1
                    continue
                name, props = r
                key = (name, tuple(sorted(props.items())))
                if key not in pidx:
                    pidx[key] = len(palette)
                    entry = {"Name": name}
                    if props: entry["Properties"] = dict(props)
                    palette.append(entry)
                blocks.append({"pos":[x-x0, y-y0, z-z0], "state": pidx[key]})

    write(dst, {"DataVersion":3955, "size":[sx,sy,sz], "palette":palette,
                "blocks":blocks, "entities":[]})
    if verbose:
        tot = sum(unmapped.values())
        print("  %-18s %2dx%2dx%-2d  %5d blocks  %3d palette  %s" % (
            src.split("/")[-1][:-10], sx,sy,sz, len(blocks), len(palette),
            ("%d unmapped (%s)" % (tot, ", ".join("%d:%d"%k for k,_ in unmapped.most_common(4)))) if tot else "clean"))
    return {"size":[sx,sy,sz], "blocks":len(blocks), "palette":len(palette), "unmapped":dict(unmapped)}

if __name__ == "__main__":
    import glob, os, json
    src_dir = sys.argv[1]; out_dir = sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    res = {}
    for f in sorted(glob.glob(src_dir+"/*.schematic")):
        name = os.path.basename(f)[:-10].lower().replace(" ","_").replace("'","")
        r = convert_file(f, "%s/lf_%s.nbt"%(out_dir,name))
        if r: res[name] = r
    json.dump({k:{kk:(vv if kk!="unmapped" else {str(a):b for a,b in vv.items()}) for kk,vv in v.items()} for k,v in res.items()}, open("lemonfox_report.json","w"), indent=1)
    print("\nconverted %d schematics" % len(res))

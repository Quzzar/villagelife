# -*- coding: utf-8 -*-
"""Finds separate buildings inside a large .schematic and crops each one out.

Works top-down: project every non-terrain block onto a 2D footprint, flood-fill
into connected clusters, then crop each cluster's 3D bounding box. Trees are
rejected by composition rather than by shape.
"""
import sys, os, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nbt import read
from nbtwrite import write
from flatten import convert

TERRAIN = {0,1,2,3,7,9,12,13,31,37,38,39,40,78,79,80,110,175,111,8,10,11,82,24,161,162,17,18,6}
FOLIAGE = {17,18,161,162,6,106}          # logs and leaves: a cluster of these is a tree
BUILDY  = {4,5,43,44,45,48,53,67,98,109,125,126,134,135,139,155,163,164,171,172,44}

def split(path, min_cells=40, gap=1, min_height=3):
    d = read(path)
    W,H,L,B,D = d['Width'],d['Height'],d['Length'],d['Blocks'],d['Data']
    def at(x,y,z):
        i=(y*L+z)*W+x; return B[i]&0xff, D[i]&0x0f

    # A column counts only if it has real vertical structure. One block deep is a
    # path or a doorstep and would weld every building in the village together.
    foot=[[0]*L for _ in range(W)]
    for z in range(L):
        for x in range(W):
            n=0
            for y in range(H):
                b=B[((y*L+z)*W+x)]&0xff
                if b and b not in TERRAIN: n+=1
            if n>=min_height: foot[x][z]=1
    # dilate so a building's parts join across small gaps
    dil=[[0]*L for _ in range(W)]
    for x in range(W):
        for z in range(L):
            if foot[x][z]:
                for dx in range(-gap,gap+1):
                    for dz in range(-gap,gap+1):
                        nx,nz=x+dx,z+dz
                        if 0<=nx<W and 0<=nz<L: dil[nx][nz]=1
    seen=[[False]*L for _ in range(W)]
    clusters=[]
    for sx in range(W):
        for sz in range(L):
            if dil[sx][sz] and not seen[sx][sz]:
                stack=[(sx,sz)]; seen[sx][sz]=True; cells=[]
                while stack:
                    x,z=stack.pop(); cells.append((x,z))
                    for dx,dz in ((1,0),(-1,0),(0,1),(0,-1),(1,1),(1,-1),(-1,1),(-1,-1)):
                        nx,nz=x+dx,z+dz
                        if 0<=nx<W and 0<=nz<L and dil[nx][nz] and not seen[nx][nz]:
                            seen[nx][nz]=True; stack.append((nx,nz))
                if len(cells)>=min_cells: clusters.append(cells)
    out=[]
    for cells in clusters:
        xs=[c[0] for c in cells]; zs=[c[1] for c in cells]
        x0,x1,z0,z1=min(xs),max(xs),min(zs),max(zs)
        comp=collections.Counter(); ys=[]
        for y in range(H):
            for z in range(z0,z1+1):
                for x in range(x0,x1+1):
                    b,_=at(x,y,z)
                    if b and b not in TERRAIN:
                        comp[b]+=1; ys.append(y)
        if not ys: continue
        tot=sum(comp.values())
        fol=sum(v for k,v in comp.items() if k in FOLIAGE)
        bld=sum(v for k,v in comp.items() if k in BUILDY)
        out.append({"x0":x0,"x1":x1,"z0":z0,"z1":z1,"y0":min(ys),"y1":max(ys),
                    "cells":len(cells),"blocks":tot,"foliage":fol/tot if tot else 0,
                    "buildy":bld/tot if tot else 0})
    out.sort(key=lambda c:-c["blocks"])
    return d, out

def crop(d, c, dst):
    W,L,B,D = d['Width'],d['Length'],d['Blocks'],d['Data']
    sx,sy,sz = c["x1"]-c["x0"]+1, c["y1"]-c["y0"]+1, c["z1"]-c["z0"]+1
    palette,pidx,blocks = [],{},[]
    for y in range(c["y0"],c["y1"]+1):
        for z in range(c["z0"],c["z1"]+1):
            for x in range(c["x0"],c["x1"]+1):
                i=(y*L+z)*W+x; b=B[i]&0xff
                if b==0 or b in TERRAIN: continue
                r=convert(b, D[i]&0x0f)
                if r is None: continue
                name,props=r; key=(name,tuple(sorted(props.items())))
                if key not in pidx:
                    pidx[key]=len(palette)
                    e={"Name":name}
                    if props: e["Properties"]=dict(props)
                    palette.append(e)
                blocks.append({"pos":[x-c["x0"],y-c["y0"],z-c["z0"]],"state":pidx[key]})
    if not blocks: return None
    write(dst, {"DataVersion":3955,"size":[sx,sy,sz],"palette":palette,"blocks":blocks,"entities":[]})
    return len(blocks)

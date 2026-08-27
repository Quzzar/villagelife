# -*- coding: utf-8 -*-
"""Score a structure for villager navigability.

A villager needs a walkable route from open ground outside the structure to a
bed: two blocks of headroom, solid footing, and no climbing more than a step.
This flood-fills exactly that.

The structure is assumed to sit on solid ground that extends one block past its
bounding box on every side. That apron matters: a tightly-cropped structure has
its walls ON the boundary, so a fill that starts on the boundary itself has
nowhere to stand and reports everything sealed.
"""
import sys, collections
sys.path.insert(0,".")
from nbt import read

PASSABLE_SUFFIX=("_door","_trapdoor","_fence_gate","torch","_sign","_banner","_carpet","_button","_pressure_plate")
PASSABLE={"minecraft:air","minecraft:cave_air","minecraft:water","minecraft:snow",
          "minecraft:short_grass","minecraft:fern","minecraft:ladder","minecraft:vine",
          "minecraft:lantern","minecraft:chain"}
def passable(n):
    return n in PASSABLE or n.endswith(PASSABLE_SUFFIX)

def analyse(path):
    d=read(path); pal=d["palette"]; W,H,L=d["size"]
    nm=[p["Name"] for p in pal]
    at={}
    for b in d["blocks"]: at[tuple(b["pos"])]=nm[b["state"]]

    M=1                                   # width of the assumed ground apron
    def blk(x,y,z):
        if 0<=x<W and 0<=y<H and 0<=z<L: return at.get((x,y,z),"minecraft:air")
        if y<0: return "minecraft:grass_block"   # the ground everything rests on
        return "minecraft:air"
    def solid(x,y,z):
        return not passable(blk(x,y,z))
    def stand(x,y,z):
        return solid(x,y-1,z) and passable(blk(x,y,z)) and passable(blk(x,y+1,z))
    def in_range(x,y,z):
        return -M<=x<W+M and 0<=y<H and -M<=z<L+M

    # A bed is two blocks. Pair foot to head via the foot's facing, so the bed is
    # counted once and is reachable if EITHER end can be stood next to: furniture
    # pushed against one end of a bed does not stop a villager using it.
    FACE={"north":(0,-1),"south":(0,1),"west":(-1,0),"east":(1,0)}
    props={}
    for b in d["blocks"]: props[tuple(b["pos"])]=pal[b["state"]].get("Properties",{})
    beds=[]
    for p,n in at.items():
        if not n.endswith("_bed"): continue
        pr=props.get(p,{})
        if pr.get("part")!="foot": continue
        dx,dz=FACE.get(pr.get("facing","north"),(0,-1))
        beds.append((p,(p[0]+dx,p[1],p[2]+dz)))
    if not beds:                                  # no part/facing data: fall back to halving
        cells=[p for p,n in at.items() if n.endswith("_bed")]
        beds=[(c,c) for c in cells[::2]]
    doors=[(p,n) for p,n in at.items() if n.endswith("_door") and not n.endswith("trapdoor")]
    if not beds: return None

    # Start from every standable cell on the apron ring, at every height.
    start=[]
    for y in range(H):
        for x in range(-M,W+M):
            for z in (-M,L+M-1):
                if stand(x,y,z): start.append((x,y,z))
        for z in range(-M,L+M):
            for x in (-M,W+M-1):
                if stand(x,y,z): start.append((x,y,z))
    seen=set(start); q=collections.deque(start)
    while q:
        x,y,z=q.popleft()
        for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)):
            for dy in (0,1,-1):                  # step up or down one block only
                n=(x+dx,y+dy,z+dz)
                if n in seen or not in_range(*n): continue
                if stand(*n): seen.add(n); q.append(n)

    def approachable(c):
        return any((c[0]+dx,c[1],c[2]+dz) in seen
                   for dx,dz in ((0,0),(1,0),(-1,0),(0,1),(0,-1)))
    reach=0; ground=0
    for foot,head in beds:
        if approachable(foot) or approachable(head): reach+=1
        if foot[1]<=1: ground+=1
    return {"beds":len(beds),"reachable":reach,
            "ground_floor_beds":ground,"doors":len(doors)//2,
            "reachable_cells":len(seen),"size":[W,H,L]}

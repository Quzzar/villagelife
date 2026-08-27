# -*- coding: utf-8 -*-
"""Pre-1.13 numeric block id + data value -> modern blockstate."""
WOOD = {0:"oak",1:"spruce",2:"birch",3:"jungle",4:"acacia",5:"dark_oak"}
STAIR_FACE = {0:"east",1:"west",2:"south",3:"north"}
def stairs(kind, d):
    return "minecraft:%s_stairs"%kind, {"facing":STAIR_FACE[d&3], "half":"top" if d&4 else "bottom", "shape":"straight"}
def slab(kind, d):
    return "minecraft:%s_slab"%kind, {"type":"top" if d&8 else "bottom", "waterlogged":"false"}
def log(kind, d):
    ax={0:"y",1:"x",2:"z"}.get((d>>2)&3)
    if ax is None: return "minecraft:%s_wood"%kind, {"axis":"y"}
    return "minecraft:%s_log"%kind, {"axis":ax}
def door(kind, d):
    if d&8: return "minecraft:%s_door"%kind, {"half":"upper","hinge":"right" if d&1 else "left","facing":"north","open":"false","powered":"false"}
    return "minecraft:%s_door"%kind, {"half":"lower","facing":STAIR_FACE[d&3],"hinge":"left","open":"true" if d&4 else "false","powered":"false"}

SIMPLE = {
 2:"grass_block",4:"cobblestone",7:"bedrock",13:"gravel",16:"coal_ore",20:"glass",
 30:"cobweb",33:"piston",37:"dandelion",41:"gold_block",46:"tnt",47:"bookshelf",
 48:"mossy_cobblestone",51:"fire",58:"crafting_table",66:"rail",72:"oak_pressure_plate",
 76:"redstone_wall_torch",80:"snow_block",84:"jukebox",85:"oak_fence",87:"netherrack",
 92:"cake",101:"iron_bars",102:"glass_pane",116:"enchanting_table",118:"cauldron",
 133:"emerald_block",140:"flower_pot",143:"oak_button",144:"skeleton_skull",145:"anvil",
 146:"trapped_chest",172:"terracotta",188:"spruce_fence",191:"dark_oak_fence",
 9:"water",23:"dispenser",131:"tripwire_hook",97:"stone",
}
STONE = {0:"stone",1:"granite",2:"polished_granite",3:"diorite",4:"polished_diorite",5:"andesite",6:"polished_andesite"}
SBRICK= {0:"stone_bricks",1:"mossy_stone_bricks",2:"cracked_stone_bricks",3:"chiseled_stone_bricks"}
FLOWER= {0:"poppy",1:"blue_orchid",2:"allium",3:"azure_bluet",4:"red_tulip",5:"orange_tulip",6:"white_tulip",7:"pink_tulip",8:"oxeye_daisy"}
WOOL  = {0:"white",1:"orange",2:"magenta",3:"light_blue",4:"yellow",5:"lime",6:"pink",7:"gray",
         8:"light_gray",9:"cyan",10:"purple",11:"blue",12:"brown",13:"green",14:"red",15:"black"}
TALL  = {0:"sunflower",1:"lilac",2:"tall_grass",3:"large_fern",4:"rose_bush",5:"peony"}

def convert(bid, d):
    """Returns (name, properties) or None for air."""
    if bid == 0: return None
    if bid in SIMPLE: return "minecraft:"+SIMPLE[bid], {}
    if bid == 1:  return "minecraft:"+STONE.get(d,"stone"), {}
    if bid == 3:  return "minecraft:coarse_dirt" if d==1 else "minecraft:dirt", {}
    if bid == 5:  return "minecraft:%s_planks"%WOOD.get(d,"oak"), {}
    if bid == 17: return log(WOOD.get(d&3,"oak"), d)
    if bid == 162:return log("acacia" if (d&1)==0 else "dark_oak", d)
    if bid == 18: return "minecraft:%s_leaves"%WOOD.get(d&3,"oak"), {"persistent":"true","distance":"7","waterlogged":"false"}
    if bid == 161:return "minecraft:%s_leaves"%("acacia" if (d&1)==0 else "dark_oak"), {"persistent":"true","distance":"7","waterlogged":"false"}
    if bid == 26: return "minecraft:red_bed", {"facing":STAIR_FACE[d&3],"part":"head" if d&8 else "foot","occupied":"false"}
    if bid == 31: return ("minecraft:short_grass" if d==1 else "minecraft:fern"), {}
    if bid == 35: return "minecraft:%s_wool"%WOOL.get(d,"white"), {}
    if bid == 38: return "minecraft:"+FLOWER.get(d,"poppy"), {}
    if bid == 43: return "minecraft:%s"%{0:"stone",3:"cobblestone",5:"stone_bricks"}.get(d,"stone"), {}
    if bid == 44: return slab({0:"smooth_stone",3:"cobblestone",5:"stone_brick"}.get(d&7,"smooth_stone"), d)
    if bid == 125:return "minecraft:%s_planks"%WOOD.get(d&7,"oak"), {}
    if bid == 126:return slab(WOOD.get(d&7,"oak"), d)
    if bid == 50: return ("minecraft:torch",{}) if d==5 else ("minecraft:wall_torch",{"facing":{1:"east",2:"west",3:"south",4:"north"}.get(d,"north")})
    if bid == 53: return stairs("oak", d)
    if bid == 67: return stairs("cobblestone", d)
    if bid == 109:return stairs("stone_brick", d)
    if bid == 134:return stairs("spruce", d)
    if bid == 135:return stairs("birch", d)
    if bid == 163:return stairs("acacia", d)
    if bid == 164:return stairs("dark_oak", d)
    if bid == 54: return "minecraft:chest", {"facing":{2:"north",3:"south",4:"west",5:"east"}.get(d,"north"),"type":"single","waterlogged":"false"}
    if bid == 61: return "minecraft:furnace", {"facing":{2:"north",3:"south",4:"west",5:"east"}.get(d,"north"),"lit":"false"}
    if bid == 63: return "minecraft:oak_sign", {"rotation":str(d&15),"waterlogged":"false"}
    if bid == 68: return "minecraft:oak_wall_sign", {"facing":{2:"north",3:"south",4:"west",5:"east"}.get(d,"north"),"waterlogged":"false"}
    if bid == 64: return door("oak", d)
    if bid == 193:return door("spruce", d)
    if bid == 194:return door("birch", d)
    if bid == 195:return door("jungle", d)
    if bid == 65: return "minecraft:ladder", {"facing":{2:"north",3:"south",4:"west",5:"east"}.get(d,"north"),"waterlogged":"false"}
    if bid == 96: return "minecraft:oak_trapdoor", {"facing":STAIR_FACE[d&3],"half":"top" if d&8 else "bottom","open":"true" if d&4 else "false","powered":"false","waterlogged":"false"}
    if bid == 106:return "minecraft:vine", {"north":"false","south":"false","east":"false","west":"false","up":"false"}
    if bid == 107:return "minecraft:oak_fence_gate", {"facing":STAIR_FACE[d&3],"open":"true" if d&4 else "false","in_wall":"false","powered":"false"}
    if bid == 183:return "minecraft:spruce_fence_gate", {"facing":STAIR_FACE[d&3],"open":"true" if d&4 else "false","in_wall":"false","powered":"false"}
    if bid == 98: return "minecraft:"+SBRICK.get(d,"stone_bricks"), {}
    if bid == 95: return "minecraft:%s_stained_glass"%WOOL.get(d,"white"), {}
    if bid == 160:return "minecraft:%s_stained_glass_pane"%WOOL.get(d,"white"), {"north":"false","south":"false","east":"false","west":"false","waterlogged":"false"}
    if bid == 139:return "minecraft:%s_wall"%("mossy_cobblestone" if d==1 else "cobblestone"), {"up":"true","north":"none","south":"none","east":"none","west":"none","waterlogged":"false"}
    if bid == 170:return "minecraft:hay_block", {"axis":{0:"y",4:"x",8:"z"}.get(d,"y")}
    if bid == 171:return "minecraft:%s_carpet"%WOOL.get(d,"white"), {}
    if bid == 175:return "minecraft:"+TALL.get(d&7,"tall_grass"), {"half":"upper" if d&8 else "lower"}
    if bid == 113:return "minecraft:nether_brick_fence", {}
    if bid == 42: return "minecraft:iron_block", {}
    if bid == 136:return stairs("jungle", d)
    if bid == 89: return "minecraft:glowstone", {}
    if bid == 39: return "minecraft:brown_mushroom", {}
    if bid == 40: return "minecraft:red_mushroom", {}
    if bid == 155:return "minecraft:quartz_block", {}
    if bid == 152:return "minecraft:redstone_block", {}
    if bid == 59: return "minecraft:wheat", {"age":str(min(d,7))}
    if bid == 60: return "minecraft:farmland", {"moisture":str(min(d,7))}
    if bid == 159:return "minecraft:%s_terracotta"%WOOL.get(d,"white"), {}
    if bid == 166:return "minecraft:barrier", {}
    if bid == 179:return "minecraft:red_sandstone", {}
    if bid == 180:return stairs("red_sandstone", d)
    if bid == 24: return "minecraft:sandstone", {}
    if bid == 12: return "minecraft:sand", {}
    if bid == 128:return stairs("sandstone", d)
    return None

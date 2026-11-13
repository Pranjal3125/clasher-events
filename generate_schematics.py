#!/usr/bin/env python3
"""Generate compact Sponge Schematic v2 arena templates for Clasher Events.
Run: python generate_schematics.py
These are editable starter builds; customize in-game with WorldEdit before public events.
"""
import gzip, struct, os, datetime

W,H,L = 64,14,64
OUT = os.path.join(os.path.dirname(__file__), 'schematics')
os.makedirs(OUT, exist_ok=True)

def nbt_string(s):
    b=s.encode('utf-8'); return struct.pack('>H',len(b))+b

def tag(t,name,payload): return bytes([t])+nbt_string(name)+payload

def int_tag(name,v): return tag(3,name,struct.pack('>i',v))
def short_tag(name,v): return tag(2,name,struct.pack('>h',v))
def int_array_tag(name,values): return tag(11,name,struct.pack('>i',len(values))+b''.join(struct.pack('>i',x) for x in values))
def byte_array_tag(name,values): return tag(7,name,struct.pack('>i',len(values))+bytes(values))
def compound_tag(name,children): return tag(10,name,b''.join(children)+b'\x00')
def list_compound_tag(name): return tag(9,name,bytes([10])+struct.pack('>i',0))
def varint(n):
    out=bytearray()
    while True:
        b=n & 0x7f; n >>= 7
        if n: out.append(b|0x80)
        else: out.append(b); return bytes(out)

def make_arena(theme):
    blocks=['minecraft:air']
    def add(b):
        if b not in blocks: blocks.append(b)
        return blocks.index(b)
    air=0
    floor=add(theme['floor']); trim=add(theme['trim']); wall=add(theme['wall']); accent=add(theme['accent']); detail=add(theme['detail']); light=add(theme['light'])
    grid=[0]*(W*H*L)
    def put(x,y,z,b):
        if 0<=x<W and 0<=y<H and 0<=z<L: grid[(y*L+z)*W+x]=add(b) if isinstance(b,str) else b
    cx=cz=31.5
    # Large square base, circular playable floor, themed outer ring.
    for x in range(W):
      for z in range(L):
        dx=x-cx; dz=z-cz; r=(dx*dx+dz*dz)**0.5
        if r<=29: put(x,0,z,floor)
        elif r<=31: put(x,0,z,trim)
        if 27<=r<=29:
          put(x,1,z,accent)
          if (x+z)%5==0: put(x,2,z,detail)
        if r>29 and r<=31:
          put(x,1,z,wall)
          if (x+z)%3==0: put(x,2,z,accent)
    # Four entry gates and corner pylons.
    for x in (5,58):
      for z in (5,58):
        for y in range(1,8):
          for dx in range(-1,2):
            for dz in range(-1,2):
              if abs(dx)==1 or abs(dz)==1: put(x+dx,y,z+dz,accent if y%3 else detail)
        put(x,8,z,light)
    # Symmetric low cover pillars around combat area.
    for x,z in [(16,16),(47,16),(16,47),(47,47),(31,12),(31,51),(12,31),(51,31)]:
      for y in range(1,5):
        for dx in (-1,0,1):
          for dz in (-1,0,1):
            if abs(dx)+abs(dz)<=2: put(x+dx,y,z+dz,accent if y==4 else wall)
      put(x,5,z,light)
    # Raised center dais with patterned ring.
    for x in range(25,39):
      for z in range(25,39):
        if max(abs(x-31),abs(z-31))<=6:
          put(x,1,z,trim if (x+z)%2==0 else floor)
        if max(abs(x-31),abs(z-31))==7: put(x,1,z,accent)
    # Spectator gallery at north edge, with stairs/rail and glow markers.
    for x in range(22,42):
      for z in range(2,7): put(x,3,z,detail if z in (2,6) else trim)
      for y in range(4,7):
        put(x,y,2,wall)
        if x%3==0: put(x,y,6,accent)
      if x%4==0: put(x,7,4,light)
    # Entrance paths.
    for t in range(10,23):
      for x,z in [(31,t),(31,63-t),(t,31),(63-t,31)]:
        put(x,1,z,trim)
    # Decorative vertical banners around edge.
    for x,z in [(31,3),(31,60),(3,31),(60,31)]:
      for y in range(3,9): put(x,y,z,accent if y%2 else detail)
    # Encode palette indices as Minecraft VarInts in YZX order.
    data=b''.join(varint(v) for v in grid)
    palette={name:i for i,name in enumerate(blocks)}
    pal_tags=[int_tag(k,v) for k,v in palette.items()]
    metadata=compound_tag('Metadata',[int_tag('WEOffsetX',-32),int_tag('WEOffsetY',0),int_tag('WEOffsetZ',-32)])
    root_children=[int_tag('Version',2),int_tag('DataVersion',4325),short_tag('Width',W),short_tag('Height',H),short_tag('Length',L),
                   int_tag('PaletteMax',len(palette)),compound_tag('Palette',pal_tags),byte_array_tag('BlockData',data),
                   list_compound_tag('BlockEntities'),list_compound_tag('Entities'),int_array_tag('Offset',[-32,0,-32]),metadata]
    raw=b'\x0a'+nbt_string('Schematic')+b''.join(root_children)+b'\x00'
    return gzip.compress(raw, compresslevel=9)


def make_nethpot():
    """Large Nether Citadel arena: 128x30x128, centered on paste origin."""
    global W, H, L
    W, H, L = 128, 30, 128
    blocks=['minecraft:air']
    def add(b):
        if b not in blocks: blocks.append(b)
        return blocks.index(b)
    names = [
        'polished_blackstone_bricks','cracked_polished_blackstone_bricks','blackstone',
        'polished_blackstone','nether_bricks','red_nether_bricks','chiseled_nether_bricks',
        'basalt','smooth_basalt','gilded_blackstone','magma_block','lava','shroomlight',
        'soul_lantern','chain','crimson_planks','crimson_fence','polished_blackstone_wall',
        'nether_brick_stairs','polished_blackstone_brick_stairs','nether_brick_slab',
        'polished_blackstone_brick_slab','blackstone_wall','crimson_trapdoor','glass','glowstone','obsidian'
    ]
    ids={name:add('minecraft:'+name) for name in names}
    grid=[0]*(W*H*L)
    def put(x,y,z,b):
        if 0<=x<W and 0<=y<H and 0<=z<L:
            grid[(y*L+z)*W+x]=ids[b] if isinstance(b,str) else b
    def disk(cx,cz,r,y,b,inner=0):
        rr=r*r; ii=inner*inner
        for x in range(max(0,cx-r),min(W,cx+r+1)):
            for z in range(max(0,cz-r),min(L,cz+r+1)):
                d=(x-cx)**2+(z-cz)**2
                if ii<=d<=rr: put(x,y,z,b)
    def pillar(x,z,y0,y1,b,r=1):
        for y in range(y0,y1+1):
            for dx in range(-r,r+1):
                for dz in range(-r,r+1):
                    if max(abs(dx),abs(dz))<=r: put(x+dx,y,z+dz,b)
    def tower(cx,cz):
        # Broad 15x15 bastion with a 24-block crenellated crown.
        for y in range(0,22):
            for dx in range(-7,8):
                for dz in range(-7,8):
                    edge=max(abs(dx),abs(dz))
                    if y<=2: put(cx+dx,y,cz+dz,'polished_blackstone_bricks')
                    elif edge==7: put(cx+dx,y,cz+dz,'red_nether_bricks' if y%4 else 'basalt')
                    elif y in (3,10,17,20): put(cx+dx,y,cz+dz,'nether_bricks')
        for dx in (-6,6):
            for dz in (-6,6):
                pillar(cx+dx,cz+dz,3,24,'nether_bricks',1)
                put(cx+dx,25,cz+dz,'shroomlight')
        for dx in range(-7,8):
            for dz in range(-7,8):
                if abs(dx)==7 or abs(dz)==7:
                    if (dx+dz)%2==0:
                        put(cx+dx,22,cz+dz,'polished_blackstone_bricks')
                        put(cx+dx,23,cz+dz,'blackstone_wall')
        for y in (7,14):
            for d in range(-4,5):
                put(cx+d,y,cz-7,'shroomlight' if d==0 else 'gilded_blackstone')
                put(cx+d,y,cz+7,'shroomlight' if d==0 else 'gilded_blackstone')
                put(cx-7,y,cz+d,'shroomlight' if d==0 else 'gilded_blackstone')
                put(cx+7,y,cz+d,'shroomlight' if d==0 else 'gilded_blackstone')
    import math
    cx=cz=63
    # Wide main combat island with detailed concentric floorwork.
    disk(cx,cz,43,0,'blackstone')
    disk(cx,cz,42,1,'polished_blackstone_bricks')
    disk(cx,cz,40,1,'polished_blackstone')
    disk(cx,cz,37,1,'cracked_polished_blackstone_bricks')
    disk(cx,cz,35,1,'polished_blackstone')
    disk(cx,cz,32,1,'polished_blackstone_bricks')
    disk(cx,cz,29,1,'polished_blackstone')
    # Radial inlays in eight directions, with a clear open central combat lane.
    for t in range(-34,35):
        for w in (-1,0,1):
            for x,z in ((cx+t,cz+w),(cx+w,cz+t),(cx+t,cz+t),(cx+t,cz-t)):
                if 0<=x<W and 0<=z<L and (x-cx)**2+(z-cz)**2<=34**2:
                    put(x,2,z,'red_nether_bricks' if w==0 else 'nether_bricks')
    # Lava moat and retaining rims around the playable island.
    disk(cx,cz,50,0,'basalt',48)
    disk(cx,cz,49,1,'magma_block',48)
    disk(cx,cz,48,1,'lava',44)
    disk(cx,cz,44,1,'polished_blackstone_bricks',43)
    # Four wide fortified bridges over the moat.
    for t in range(39,55):
        for w in range(-6,7):
            coords=((cx+w,cz-t),(cx+w,cz+t),(cx-t,cz+w),(cx+t,cz+w))
            for x,z in coords:
                put(x,2,z,'nether_bricks')
                if abs(w) in (5,6):
                    put(x,3,z,'crimson_fence')
                    if t%4==0: put(x,4,z,'soul_lantern')
    # Outer ring wall: thick, high, and crenellated.
    for y in range(0,11):
        disk(cx,cz,59,y,'basalt',55)
        disk(cx,cz,55,y,'red_nether_bricks',54)
    disk(cx,cz,59,11,'nether_bricks',55)
    for angle in range(0,360,7):
        a=math.radians(angle)
        x=round(cx+57*math.cos(a)); z=round(cz+57*math.sin(a))
        put(x,12,z,'polished_blackstone_bricks')
        if angle%14==0: put(x,13,z,'blackstone_wall')
    # Eight monumental bastion towers around the curtain wall.
    for angle in range(0,360,45):
        a=math.radians(angle)
        tx=round(cx+55*math.cos(a)); tz=round(cz+55*math.sin(a))
        tower(tx,tz)
        # External chains and lanterns beneath the tower shoulders.
        for y in range(13,20): put(tx+9,y,tz,'chain')
        put(tx+9,20,tz,'shroomlight')
    # Raised central keep: tiered plinth and a compact, climbable central tower.
    for y,r,b in [(1,17,'blackstone'),(2,15,'polished_blackstone_bricks'),(3,13,'red_nether_bricks'),(4,11,'polished_blackstone')]:
        disk(cx,cz,r,y,b)
    disk(cx,cz,9,5,'polished_blackstone_bricks')
    disk(cx,cz,7,6,'chiseled_nether_bricks')
    # Four short corner keeps around the centre; preserve the middle sightline.
    for dx,dz in [(-14,-14),(-14,14),(14,-14),(14,14)]:
        pillar(cx+dx,cz+dz,2,12,'nether_bricks',2)
        put(cx+dx,13,cz+dz,'shroomlight')
        for y in (5,9): put(cx+dx+2,y,cz+dz,'gilded_blackstone')
    # Cover plinths distributed around the outer combat ring.
    for dx,dz in [(-28,-18),(28,-18),(-28,18),(28,18),(-18,-28),(18,-28),(-18,28),(18,28)]:
        x,z=cx+dx,cz+dz
        for y in range(2,8):
            for ox in range(-2,3):
                for oz in range(-2,3):
                    if abs(ox)+abs(oz)<=3: put(x+ox,y,z+oz,'basalt' if y<7 else 'polished_blackstone_bricks')
        put(x,8,z,'shroomlight')
    # Elevated north spectator gallery aligned to the existing audience spawn (0,87,-28).
    for x in range(42,85):
        for z in range(31,52):
            put(x,7,z,'nether_bricks')
            if x in (42,84) or z in (31,51): put(x,8,z,'red_nether_bricks')
        if x%5==0:
            for y in range(2,7): put(x,31,y,'basalt')
            put(x,9,41,'soul_lantern')
    for x in range(45,83,6):
        put(x,9,32,'shroomlight')
    # Two raised side balconies with solid supports and rails.
    for side in (-1,1):
        bx=cx+side*43
        for x in range(bx-5,bx+6):
            for z in range(51,76):
                put(x,6,z,'polished_blackstone_bricks')
                if x in (bx-5,bx+5) or z in (51,75): put(x,7,z,'blackstone_wall')
        for z in (55,63,71): put(bx,8,z,'soul_lantern')
    # Four tall standards above each bridge entrance.
    for x,z in [(63,8),(63,118),(8,63),(118,63)]:
        for y in range(3,18): put(x,y,z,'nether_bricks')
        for dx in (-2,-1,0,1,2):
            for y in range(11,16): put(x+dx,y,z,'crimson_planks' if (dx+y)%2 else 'red_nether_bricks')
        put(x,18,z,'shroomlight')
    # Repeating wall sconces make the perimeter readable at night.
    for angle in range(0,360,15):
        a=math.radians(angle)
        lx=round(cx+53*math.cos(a)); lz=round(cz+53*math.sin(a))
        pillar(lx,lz,3,9,'basalt',1)
        put(lx,10,lz,'shroomlight')
    # Sponge Schematic v2, centered at the arena paste point.
    data=b''.join(varint(v) for v in grid)
    palette={name:i for i,name in enumerate(blocks)}
    pal_tags=[int_tag(k,v) for k,v in palette.items()]
    metadata=compound_tag('Metadata',[int_tag('WEOffsetX',-64),int_tag('WEOffsetY',0),int_tag('WEOffsetZ',-64)])
    root_children=[int_tag('Version',2),int_tag('DataVersion',4325),short_tag('Width',W),short_tag('Height',H),short_tag('Length',L),
                   int_tag('PaletteMax',len(palette)),compound_tag('Palette',pal_tags),byte_array_tag('BlockData',data),
                   list_compound_tag('BlockEntities'),list_compound_tag('Entities'),int_array_tag('Offset',[-64,0,-64]),metadata]
    raw=b'\x0a'+nbt_string('Schematic')+b''.join(root_children)+b'\x00'
    W,H,L=64,14,64
    return gzip.compress(raw,compresslevel=9)

themes={
'nethpot':dict(floor='minecraft:polished_blackstone_bricks',trim='minecraft:cracked_polished_blackstone_bricks',wall='minecraft:basalt',accent='minecraft:nether_bricks',detail='minecraft:polished_blackstone',light='minecraft:shroomlight'),
'axe-shield':dict(floor='minecraft:stone_bricks',trim='minecraft:cracked_stone_bricks',wall='minecraft:deepslate_bricks',accent='minecraft:iron_block',detail='minecraft:oak_planks',light='minecraft:lantern'),
'build-uhc':dict(floor='minecraft:grass_block',trim='minecraft:coarse_dirt',wall='minecraft:stone',accent='minecraft:oak_log',detail='minecraft:oak_leaves',light='minecraft:glowstone'),
'sword':dict(floor='minecraft:polished_andesite',trim='minecraft:quartz_bricks',wall='minecraft:stone_bricks',accent='minecraft:chiseled_quartz_block',detail='minecraft:quartz_pillar',light='minecraft:sea_lantern'),
'uhc':dict(floor='minecraft:podzol',trim='minecraft:moss_block',wall='minecraft:stone',accent='minecraft:spruce_log',detail='minecraft:spruce_leaves',light='minecraft:lantern'),
'crystal':dict(floor='minecraft:end_stone_bricks',trim='minecraft:obsidian',wall='minecraft:purpur_block',accent='minecraft:crying_obsidian',detail='minecraft:purpur_pillar',light='minecraft:sea_lantern'),
'smp':dict(floor='minecraft:mossy_cobblestone',trim='minecraft:cracked_stone_bricks',wall='minecraft:cobblestone',accent='minecraft:dark_oak_log',detail='minecraft:mossy_stone_bricks',light='minecraft:lantern'),
'diamond-pot':dict(floor='minecraft:packed_ice',trim='minecraft:blue_ice',wall='minecraft:prismarine_bricks',accent='minecraft:diamond_block',detail='minecraft:cyan_terracotta',light='minecraft:sea_lantern'),
}
for name,theme in themes.items():
    path=os.path.join(OUT,name+'.schem')
    payload = make_nethpot() if name == 'nethpot' else make_arena(theme)
    with open(path,'wb') as f: f.write(payload)
    print(path, os.path.getsize(path))

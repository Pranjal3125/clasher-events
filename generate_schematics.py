#!/usr/bin/env python3
"""Generate 8 unique, PvP-balanced Sponge Schematic v2 arenas for Clasher Events v2.0.

Each arena has distinct architecture tailored to its PvP kit/mode, non-slippery playable
floors, high perimeter walls, elevated spectator areas, and mathematically verified
spawn clearance (solid floor at y-1, pure air at y and y+1).

Run: python generate_schematics.py
"""

from __future__ import annotations

import gzip
import math
import os
import shutil
import struct

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT_DIRS = [
    os.path.join(ROOT, "schematics"),
    os.path.join(ROOT, "src", "main", "resources", "schematics"),
]
ARENAS_YML = os.path.join(ROOT, "src", "main", "resources", "arenas.yml")


# ---------------------------------------------------------------------------
# Sponge Schematic v2 NBT Encoder
# ---------------------------------------------------------------------------

def nbt_string(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def tag(t: int, name: str, payload: bytes) -> bytes:
    return bytes([t]) + nbt_string(name) + payload


def int_tag(name: str, v: int) -> bytes:
    return tag(3, name, struct.pack(">i", v))


def short_tag(name: str, v: int) -> bytes:
    return tag(2, name, struct.pack(">h", v))


def int_array_tag(name: str, values: list[int]) -> bytes:
    return tag(11, name, struct.pack(">i", len(values)) + b"".join(struct.pack(">i", x) for x in values))


def byte_array_tag(name: str, values: bytes) -> bytes:
    return tag(7, name, struct.pack(">i", len(values)) + values)


def compound_tag(name: str, children: list[bytes]) -> bytes:
    return tag(10, name, b"".join(children) + b"\x00")


def list_compound_tag(name: str) -> bytes:
    return tag(9, name, bytes([10]) + struct.pack(">i", 0))


def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


class VoxelCanvas:
    """3D voxel builder centered horizontally around (cx, cz) with offset (-cx, 0, -cz)."""

    def __init__(self, width: int, height: int, length: int):
        self.w = width
        self.h = height
        self.l = length
        self.cx = width // 2
        self.cz = length // 2
        self.palette: list[str] = ["minecraft:air"]
        self.palette_idx: dict[str, int] = {"minecraft:air": 0}
        self.grid: list[int] = [0] * (width * height * length)

    def block_id(self, name: str) -> int:
        full = name if ":" in name else f"minecraft:{name}"
        idx = self.palette_idx.get(full)
        if idx is None:
            idx = len(self.palette)
            self.palette.append(full)
            self.palette_idx[full] = idx
        return idx

    def put(self, x: int, y: int, z: int, block: str) -> None:
        if 0 <= x < self.w and 0 <= y < self.h and 0 <= z < self.l:
            self.grid[(y * self.l + z) * self.w + x] = self.block_id(block)

    def get(self, x: int, y: int, z: int) -> str:
        if 0 <= x < self.w and 0 <= y < self.h and 0 <= z < self.l:
            return self.palette[self.grid[(y * self.l + z) * self.w + x]]
        return "minecraft:air"

    def rel_put(self, dx: int, y: int, dz: int, block: str) -> None:
        self.put(self.cx + dx, y, self.cz + dz, block)

    def rel_get(self, dx: int, y: int, dz: int) -> str:
        return self.get(self.cx + dx, y, self.cz + dz)

    def disk(self, r_outer: float, y: int, block: str, r_inner: float = 0.0, cx: int | None = None, cz: int | None = None) -> None:
        ox = self.cx if cx is None else cx
        oz = self.cz if cz is None else cz
        ro2 = r_outer * r_outer
        ri2 = r_inner * r_inner
        ir = int(math.ceil(r_outer))
        for x in range(max(0, ox - ir), min(self.w, ox + ir + 1)):
            for z in range(max(0, oz - ir), min(self.l, oz + ir + 1)):
                d2 = (x - ox) ** 2 + (z - oz) ** 2
                if ri2 <= d2 <= ro2:
                    self.put(x, y, z, block)

    def fill_box(self, dx0: int, y0: int, dz0: int, dx1: int, y1: int, dz1: int, block: str) -> None:
        for y in range(min(y0, y1), max(y0, y1) + 1):
            for dx in range(min(dx0, dx1), max(dx0, dx1) + 1):
                for dz in range(min(dz0, dz1), max(dz0, dz1) + 1):
                    self.rel_put(dx, y, dz, block)

    def pillar(self, dx: int, dz: int, y0: int, y1: int, block: str, r: int = 1) -> None:
        for y in range(y0, y1 + 1):
            for ox in range(-r, r + 1):
                for oz in range(-r, r + 1):
                    self.rel_put(dx + ox, y, dz + oz, block)

    def clear_cylinder(self, dx: int, dz: int, y0: int, y1: int, radius: float) -> None:
        r2 = radius * radius
        ir = int(math.ceil(radius))
        for y in range(y0, y1 + 1):
            for ox in range(-ir, ir + 1):
                for oz in range(-ir, ir + 1):
                    if ox * ox + oz * oz <= r2:
                        self.rel_put(dx + ox, y, dz + oz, "air")

    def add_spectator_box(self, z_center: int, y_floor: int, floor_blk: str, rail_blk: str, pillar_blk: str, light_blk: str) -> None:
        # Solid supported elevated viewing balcony on the north edge
        for dx in range(-14, 15):
            for dz in range(z_center - 4, z_center + 5):
                self.rel_put(dx, y_floor, dz, floor_blk)
                self.rel_put(dx, y_floor + 1, dz, "air")
                self.rel_put(dx, y_floor + 2, dz, "air")
                self.rel_put(dx, y_floor + 3, dz, "air")
                if abs(dx) == 14 or dz in (z_center - 4, z_center + 4):
                    self.rel_put(dx, y_floor + 1, dz, rail_blk)
            if dx % 7 == 0:
                for y in range(1, y_floor):
                    self.rel_put(dx, y, z_center - 4, pillar_blk)
                    self.rel_put(dx, y, z_center + 4, pillar_blk)
                self.rel_put(dx, y_floor + 2, z_center - 4, pillar_blk)
                self.rel_put(dx, y_floor + 3, z_center - 4, light_blk)
        # Ensure clean viewing air in front of spectator spawn
        self.fill_box(-5, y_floor + 1, z_center - 2, 5, y_floor + 4, z_center + 2, "air")

    def add_tree(self, dx: int, y_base: int, dz: int, log_blk: str, leaf_blk: str, trunk_h: int = 5, pine: bool = False) -> None:
        for y in range(y_base, y_base + trunk_h):
            self.rel_put(dx, y, dz, log_blk)
        top = y_base + trunk_h
        if pine:
            for layer, rad in enumerate([3, 2, 2, 1, 1, 0]):
                yy = top - 2 + layer
                for ox in range(-rad, rad + 1):
                    for oz in range(-rad, rad + 1):
                        if abs(ox) + abs(oz) <= rad + 1:
                            if not (ox == 0 and oz == 0 and yy < top):
                                self.rel_put(dx + ox, yy, dz + oz, leaf_blk)
            self.rel_put(dx, top + 4, dz, leaf_blk)
        else:
            for yy in range(top - 2, top + 2):
                rad = 2 if yy <= top - 1 else 1
                for ox in range(-rad, rad + 1):
                    for oz in range(-rad, rad + 1):
                        if abs(ox) == rad and abs(oz) == rad and yy >= top:
                            continue
                        if not (ox == 0 and oz == 0 and yy < top):
                            self.rel_put(dx + ox, yy, dz + oz, leaf_blk)

    def encode(self) -> bytes:
        data = b"".join(varint(v) for v in self.grid)
        pal_tags = [int_tag(k, i) for i, k in enumerate(self.palette)]
        off_x, off_y, off_z = -self.cx, 0, -self.cz
        metadata = compound_tag(
            "Metadata",
            [int_tag("WEOffsetX", off_x), int_tag("WEOffsetY", off_y), int_tag("WEOffsetZ", off_z)],
        )
        root_children = [
            int_tag("Version", 2),
            int_tag("DataVersion", 4325),
            short_tag("Width", self.w),
            short_tag("Height", self.h),
            short_tag("Length", self.l),
            int_tag("PaletteMax", len(self.palette)),
            compound_tag("Palette", pal_tags),
            byte_array_tag("BlockData", data),
            list_compound_tag("BlockEntities"),
            list_compound_tag("Entities"),
            int_array_tag("Offset", [off_x, off_y, off_z]),
            metadata,
        ]
        raw = b"\x0a" + nbt_string("Schematic") + b"".join(root_children) + b"\x00"
        return gzip.compress(raw, compresslevel=9)


# ---------------------------------------------------------------------------
# 1. NethPot — Nether Citadel (128x32x128) — Open Center, Fixed Gallery!
# ---------------------------------------------------------------------------

def build_nethpot() -> VoxelCanvas:
    c = VoxelCanvas(128, 32, 128)
    # Solid bedrock/blackstone subfloor + flat flush playable combat deck at y=1
    c.disk(43, 0, "blackstone")
    c.disk(42, 1, "polished_blackstone_bricks")
    c.disk(36, 1, "polished_blackstone")
    c.disk(28, 1, "cracked_polished_blackstone_bricks")
    c.disk(20, 1, "polished_blackstone")
    c.disk(10, 1, "chiseled_polished_blackstone")
    c.disk(5, 1, "gilded_blackstone")
    c.disk(3, 1, "polished_blackstone")

    # Flush radial walkways at y=1 (no trip ledges!)
    for t in range(-40, 41):
        for w in (-1, 0, 1):
            for dx, dz in ((t, w), (w, t)):
                if dx * dx + dz * dz <= 41 * 41:
                    c.rel_put(dx, 1, dz, "red_nether_bricks" if w == 0 else "nether_bricks")

    # Lava moat & retaining rim
    c.disk(51, 0, "basalt", 43)
    c.disk(49, 1, "magma_block", 48)
    c.disk(48, 1, "lava", 44)
    c.disk(44, 1, "polished_blackstone_bricks", 43)

    # 4 fortified bridges over the lava moat
    for t in range(42, 55):
        for w in range(-5, 6):
            for dx, dz in ((w, -t), (w, t), (-t, w), (t, w)):
                c.rel_put(dx, 1, dz, "nether_bricks")
                if abs(w) == 5:
                    c.rel_put(dx, 2, dz, "crimson_fence")
                    if t % 4 == 0:
                        c.rel_put(dx, 3, dz, "soul_lantern")

    # High crenellated outer curtain wall (y=0..15)
    for y in range(0, 14):
        c.disk(60, y, "basalt", 55)
        c.disk(55, y, "red_nether_bricks", 54)
    c.disk(60, 14, "nether_bricks", 55)
    for deg in range(0, 360, 6):
        rad = math.radians(deg)
        dx, dz = round(57 * math.cos(rad)), round(57 * math.sin(rad))
        c.rel_put(dx, 15, dz, "polished_blackstone_bricks")
        if deg % 12 == 0:
            c.rel_put(dx, 16, dz, "blackstone_wall")

    # 8 monumental bastion towers around the curtain wall
    for deg in range(0, 360, 45):
        rad = math.radians(deg)
        tx, tz = round(55 * math.cos(rad)), round(55 * math.sin(rad))
        for y in range(0, 24):
            for ox in range(-6, 7):
                for oz in range(-6, 7):
                    edge = max(abs(ox), abs(oz))
                    if y <= 2:
                        c.rel_put(tx + ox, y, tz + oz, "polished_blackstone_bricks")
                    elif edge == 6:
                        c.rel_put(tx + ox, y, tz + oz, "red_nether_bricks" if y % 4 else "basalt")
                    elif y in (10, 18, 22):
                        c.rel_put(tx + ox, y, tz + oz, "nether_bricks")
        for ox in (-5, 5):
            for oz in (-5, 5):
                c.pillar(tx + ox, tz + oz, 3, 26, "nether_bricks", 1)
                c.rel_put(tx + ox, 27, tz + oz, "shroomlight")

    # 8 balanced low-profile cover plinths at r=26 (leaves center & spawns 100% open!)
    for deg in (22.5, 67.5, 112.5, 157.5, 202.5, 247.5, 292.5, 337.5):
        rad = math.radians(deg)
        px, pz = round(26 * math.cos(rad)), round(26 * math.sin(rad))
        c.pillar(px, pz, 2, 5, "polished_blackstone_bricks", 1)
        c.rel_put(px, 6, pz, "shroomlight")

    # Properly supported north spectator gallery (y=11, clear of combat deck!)
    c.add_spectator_box(-32, 11, "nether_bricks", "blackstone_wall", "basalt", "shroomlight")
    return c


# ---------------------------------------------------------------------------
# 2. Sword — Grand Colosseum (96x28x96)
# ---------------------------------------------------------------------------

def build_sword() -> VoxelCanvas:
    c = VoxelCanvas(96, 28, 96)
    # Flat sand & smooth sandstone Roman gladiator combat floor
    c.disk(36, 0, "smooth_sandstone")
    c.disk(34, 1, "smooth_sandstone")
    c.disk(31, 1, "sandstone")
    c.disk(26, 1, "smooth_sandstone")
    c.disk(12, 1, "cut_sandstone")
    c.disk(6, 1, "chiseled_quartz_block")
    c.disk(3, 1, "gold_block")

    # Flush marble cross lanes
    for t in range(-32, 33):
        c.rel_put(t, 1, 0, "quartz_bricks")
        c.rel_put(0, 1, t, "quartz_bricks")

    # Multi-tiered Roman Colosseum amphitheater walls with arched openings
    for y in range(1, 20):
        c.disk(45, y, "smooth_sandstone", 35)
        if y <= 6:
            c.disk(36, y, "quartz_bricks", 34)
        elif y <= 12:
            c.disk(39, y, "cut_sandstone", 36)
        else:
            c.disk(42, y, "quartz_bricks", 39)

    # Stadium arched colonnade around upper tiers
    for deg in range(0, 360, 10):
        rad = math.radians(deg)
        px, pz = round(38 * math.cos(rad)), round(38 * math.sin(rad))
        for y in range(7, 19):
            c.rel_put(px, y, pz, "quartz_pillar")
        c.rel_put(px, 20, pz, "sea_lantern")
        c.rel_put(px, 21, pz, "chiseled_quartz_block")

    # 4 grand gladiator entrance gates (N/S/E/W)
    for axis in ("x", "z"):
        for sign in (-1, 1):
            for t in range(34, 43):
                for w in range(-3, 4):
                    for y in range(2, 7):
                        dx, dz = (sign * t, w) if axis == "x" else (w, sign * t)
                        if abs(w) == 3 or y == 6:
                            c.rel_put(dx, y, dz, "quartz_pillar")
                        else:
                            c.rel_put(dx, y, dz, "iron_bars" if t == 37 else "air")

    # 4 subtle corner Roman columns at r=21 for Sword tactical spacing
    for dx, dz in ((-15, -15), (15, -15), (-15, 15), (15, 15)):
        c.pillar(dx, dz, 2, 6, "quartz_pillar", 1)
        c.rel_put(dx, 7, dz, "sea_lantern")

    # Elevated Emperor spectator gallery on north side
    c.add_spectator_box(-28, 9, "quartz_bricks", "sandstone_wall", "quartz_pillar", "sea_lantern")
    return c


# ---------------------------------------------------------------------------
# 3. Crystal PvP — End Rift (104x26x104) — Bedrock Subfloor + Obsidian Deck
# ---------------------------------------------------------------------------

def build_crystal() -> VoxelCanvas:
    c = VoxelCanvas(104, 26, 104)
    # Blast-proof bedrock sub-layer so crystals can NEVER hole the arena!
    c.disk(42, 0, "bedrock")
    c.disk(40, 1, "end_stone_bricks")
    c.disk(34, 1, "obsidian")
    c.disk(30, 1, "end_stone_bricks")
    c.disk(16, 1, "obsidian")
    c.disk(12, 1, "end_stone_bricks")
    c.disk(4, 1, "crying_obsidian")

    # Flush obsidian placement grid strips (ideal for Crystal PvP!)
    for t in range(-34, 35):
        for offset in (-12, 0, 12):
            if t * t + offset * offset <= 37 * 37:
                c.rel_put(t, 1, offset, "obsidian")
                c.rel_put(offset, 1, t, "obsidian")

    # High obsidian & purpur containment perimeter wall
    for y in range(1, 14):
        c.disk(47, y, "obsidian", 41)
        if y % 3 == 0:
            c.disk(42, y, "purpur_block", 40)
    c.disk(47, 14, "purpur_pillar", 41)

    # 8 End obsidian monolith towers with beacon crystals
    for deg in range(0, 360, 45):
        rad = math.radians(deg)
        tx, tz = round(43 * math.cos(rad)), round(43 * math.sin(rad))
        c.pillar(tx, tz, 2, 20, "obsidian", 2)
        for y in (6, 12, 18):
            c.rel_put(tx, y, tz, "crying_obsidian")
        c.rel_put(tx, 21, tz, "purpur_block")
        c.rel_put(tx, 22, tz, "sea_lantern")

    # Low obsidian cover blocks (1-2 high, clean Crystal PvP sightlines)
    for dx, dz in ((-18, -18), (18, -18), (-18, 18), (18, 18)):
        c.fill_box(dx - 1, 2, dz - 1, dx + 1, 3, dz + 1, "obsidian")
        c.rel_put(dx, 4, dz, "crying_obsidian")

    c.add_spectator_box(-31, 10, "purpur_block", "end_stone_brick_wall", "obsidian", "sea_lantern")
    return c


# ---------------------------------------------------------------------------
# 4. Build UHC — Verdant Basin (96x30x96) — Natural Terrain, Trees, Cliffs
# ---------------------------------------------------------------------------

def build_build_uhc() -> VoxelCanvas:
    c = VoxelCanvas(96, 30, 96)
    c.disk(44, 0, "bedrock")
    c.disk(43, 1, "dirt")
    # Rolling grassy basin floor at y=1..2
    for dx in range(-38, 39):
        for dz in range(-38, 39):
            r = math.hypot(dx, dz)
            if r > 37:
                continue
            # Keep central & spawn lanes flat at y=1; gentle outer grass mounds at r>24
            h = 1
            if r > 25:
                bump = math.sin(dx * 0.22) * math.cos(dz * 0.22)
                if bump > 0.35:
                    h = 2
            for y in range(1, h):
                c.rel_put(dx, y, dz, "dirt")
            blk = "grass_block" if (dx * 7 + dz * 13) % 9 else "moss_block"
            if abs(dx) <= 2 and abs(dz) <= 24:
                blk = "coarse_dirt" if (dx + dz) % 3 else "grass_block"
            c.rel_put(dx, h, dz, blk)

    # Decorative shallow stream along z=0 outside spawn pads, with oak footbridges
    for dx in range(-33, -11):
        if -24 <= dx <= -20 or -16 <= dx <= -12:
            c.fill_box(dx, 1, -1, dx, 1, 1, "oak_planks")
        else:
            c.rel_put(dx, 1, 0, "water")
            c.rel_put(dx, 1, -1, "gravel")
            c.rel_put(dx, 1, 1, "gravel")
    for dx in range(11, 34):
        if 20 <= dx <= 24 or 12 <= dx <= 16:
            c.fill_box(dx, 1, -1, dx, 1, 1, "oak_planks")
        else:
            c.rel_put(dx, 1, 0, "water")
            c.rel_put(dx, 1, -1, "gravel")
            c.rel_put(dx, 1, 1, "gravel")

    # Small oak bridge over center stream & wooden trim
    c.fill_box(-5, 1, -3, 5, 1, 3, "oak_planks")

    # Steep natural stone/deepslate cliff perimeter (height 18)
    for dx in range(-45, 46):
        for dz in range(-45, 46):
            r = math.hypot(dx, dz)
            if 36.5 <= r <= 44.5:
                wall_h = int(14 + 4 * math.sin(dx * 0.18 + dz * 0.15))
                if r < 39:
                    wall_h = max(6, wall_h - 6)
                for y in range(1, wall_h + 1):
                    blk = "deepslate" if y < 5 else ("stone" if y < wall_h else "grass_block")
                    c.rel_put(dx, y, dz, blk)

    # Perimeter trees & boulders (away from all spawn points!)
    for tx, tz in ((-24, -22), (24, -22), (-24, 22), (24, 22), (-29, 10), (29, -10), (10, 29), (-10, -29)):
        c.add_tree(tx, 2, tz, "oak_log", "oak_leaves", trunk_h=5, pine=False)
    for bx, bz in ((-14, 16), (14, -16), (16, 14), (-16, -14)):
        c.fill_box(bx - 1, 2, bz - 1, bx + 1, 3, bz + 1, "cobblestone")
        c.rel_put(bx, 4, bz, "mossy_cobblestone")

    c.add_spectator_box(-28, 12, "oak_planks", "oak_fence", "oak_log", "glowstone")
    return c


# ---------------------------------------------------------------------------
# 5. Axe & Shield — Ironkeep Fortress (96x28x96)
# ---------------------------------------------------------------------------

def build_axe_shield() -> VoxelCanvas:
    c = VoxelCanvas(96, 28, 96)
    c.disk(38, 0, "deepslate_bricks")
    c.disk(36, 1, "stone_bricks")
    c.disk(30, 1, "polished_andesite")
    c.disk(20, 1, "stone_bricks")
    c.disk(10, 1, "cracked_stone_bricks")
    c.disk(4, 1, "iron_block")

    # Flush fortified cross roads
    for t in range(-34, 35):
        for w in (-1, 0, 1):
            c.rel_put(t, 1, w, "deepslate_tiles" if w == 0 else "deepslate_bricks")
            c.rel_put(w, 1, t, "deepslate_tiles" if w == 0 else "deepslate_bricks")

    # Square-ish fortress curtain walls with corner watchtowers
    for y in range(1, 14):
        c.disk(44, y, "deepslate_bricks", 36)
        if y % 4 == 0:
            c.disk(37, y, "iron_block", 35)
    for deg in range(0, 360, 8):
        rad = math.radians(deg)
        dx, dz = round(40 * math.cos(rad)), round(40 * math.sin(rad))
        c.rel_put(dx, 14, dz, "stone_bricks")
        if deg % 16 == 0:
            c.rel_put(dx, 15, dz, "cobblestone_wall")

    # 4 heavy Ironkeep corner watchtowers
    for tx, tz in ((-30, -30), (30, -30), (-30, 30), (30, 30)):
        c.pillar(tx, tz, 1, 21, "deepslate_bricks", 4)
        c.fill_box(tx - 3, 2, tz - 3, tx + 3, 19, tz + 3, "air")
        c.fill_box(tx - 4, 21, tz - 4, tx + 4, 21, tz + 4, "stone_bricks")
        c.rel_put(tx, 22, tz, "lantern")

    # Tactical shield-duel cover bastions at r=20
    for dx, dz in ((-16, -16), (16, -16), (-16, 16), (16, 16)):
        c.pillar(dx, dz, 2, 5, "stone_bricks", 1)
        c.rel_put(dx, 6, dz, "iron_block")
        c.rel_put(dx, 7, dz, "lantern")

    c.add_spectator_box(-28, 10, "oak_planks", "stone_brick_wall", "deepslate_bricks", "lantern")
    return c


# ---------------------------------------------------------------------------
# 6. UHC — Wild Frontier (104x30x104) — Alpine Taiga + Campfire Ruins
# ---------------------------------------------------------------------------

def build_uhc() -> VoxelCanvas:
    c = VoxelCanvas(104, 30, 104)
    c.disk(46, 0, "bedrock")
    for dx in range(-40, 41):
        for dz in range(-40, 41):
            r = math.hypot(dx, dz)
            if r > 39:
                continue
            blk = "podzol" if (dx * 3 + dz * 5) % 5 == 0 else ("moss_block" if (dx + dz) % 7 == 0 else "grass_block")
            if r <= 8:
                blk = "coarse_dirt" if (dx + dz) % 2 else "cobblestone"
            c.rel_put(dx, 1, dz, blk)

    # Steep alpine cliff walls
    for dx in range(-48, 49):
        for dz in range(-48, 49):
            r = math.hypot(dx, dz)
            if 39 <= r <= 47:
                wh = int(15 + 4 * math.cos(dx * 0.14 - dz * 0.16))
                for y in range(1, wh + 1):
                    blk = "stone" if y < wh - 1 else ("snow_block" if y > 16 else "spruce_leaves")
                    c.rel_put(dx, y, dz, blk)

    # Tall spruce pines around outer forest ring
    for deg in range(15, 360, 30):
        rad = math.radians(deg)
        tx, tz = round(31 * math.cos(rad)), round(31 * math.sin(rad))
        c.add_tree(tx, 2, tz, "spruce_log", "spruce_leaves", trunk_h=6, pine=True)

    # Broken wooden outpost pillars at mid-ring
    for dx, dz in ((-16, -16), (16, -16), (-16, 16), (16, 16)):
        for y in range(2, 6):
            c.rel_put(dx, y, dz, "spruce_log")
        c.rel_put(dx, 6, dz, "lantern")

    c.add_spectator_box(-31, 12, "spruce_planks", "spruce_fence", "spruce_log", "lantern")
    return c


# ---------------------------------------------------------------------------
# 7. SMP — Fallen Kingdom (104x28x104) — Ruined Castle Citadel (Team Wars!)
# ---------------------------------------------------------------------------

def build_smp() -> VoxelCanvas:
    c = VoxelCanvas(104, 28, 104)
    c.disk(43, 0, "cobblestone")
    for dx in range(-39, 40):
        for dz in range(-39, 40):
            if dx * dx + dz * dz > 39 * 39:
                continue
            h = (dx * 11 + dz * 17) % 10
            blk = "mossy_stone_bricks" if h < 3 else ("cracked_stone_bricks" if h < 5 else "stone_bricks")
            c.rel_put(dx, 1, dz, blk)

    # Flush red vs blue subtle team lanes (West vs East for Team Wars!)
    for dx in range(-36, -6):
        for dz in (-2, -1, 0, 1, 2):
            c.rel_put(dx, 1, dz, "red_terracotta" if abs(dz) <= 1 else "polished_andesite")
    for dx in range(7, 37):
        for dz in (-2, -1, 0, 1, 2):
            c.rel_put(dx, 1, dz, "blue_terracotta" if abs(dz) <= 1 else "polished_andesite")
    c.disk(6, 1, "chiseled_stone_bricks")

    # Ruined castle perimeter walls with varying weathered height
    for dx in range(-47, 48):
        for dz in range(-47, 48):
            r = math.hypot(dx, dz)
            if 39 <= r <= 46:
                wh = int(12 + 4 * math.sin(dx * 0.25) * math.cos(dz * 0.25))
                for y in range(1, wh + 1):
                    blk = "mossy_cobblestone" if (dx + y + dz) % 4 == 0 else "stone_bricks"
                    c.rel_put(dx, y, dz, blk)

    # Ruined stone archways at mid-ground for Team Wars flanking
    for dx, dz in ((-18, -14), (-18, 14), (18, -14), (18, 14)):
        c.pillar(dx, dz, 2, 7, "mossy_stone_bricks", 1)
        c.rel_put(dx, 8, dz, "lantern")

    c.add_spectator_box(-31, 11, "dark_oak_planks", "mossy_stone_brick_wall", "dark_oak_log", "lantern")
    return c


# ---------------------------------------------------------------------------
# 8. Diamond Pot — Frostglass Temple (96x28x96) — 100% NON-SLIPPERY FLOOR!
# ---------------------------------------------------------------------------

def build_diamond_pot() -> VoxelCanvas:
    c = VoxelCanvas(96, 28, 96)
    # Non-slippery crisp frosted marble & prismarine combat deck (ZERO ice on walkable floor!)
    c.disk(38, 0, "prismarine_bricks")
    c.disk(36, 1, "quartz_bricks")
    c.disk(31, 1, "polished_diorite")
    c.disk(24, 1, "quartz_bricks")
    c.disk(14, 1, "prismarine_bricks")
    c.disk(8, 1, "quartz_bricks")
    c.disk(4, 1, "diamond_block")
    c.disk(2, 1, "sea_lantern")

    # Flush cyan terracotta & dark prismarine geometric lines (high contrast for potion splashes)
    for t in range(-34, 35):
        c.rel_put(t, 1, 0, "cyan_terracotta")
        c.rel_put(0, 1, t, "cyan_terracotta")

    # Frosted temple walls (packed_ice/blue_ice ONLY on non-walkable outer crystal walls!)
    for y in range(1, 15):
        c.disk(44, y, "prismarine_bricks", 37)
        if y in (4, 5, 9, 10):
            c.disk(38, y, "packed_ice", 36)
        elif y == 14:
            c.disk(44, y, "quartz_bricks", 37)

    # 8 glowing Frostglass crystal spires around perimeter
    for deg in range(0, 360, 45):
        rad = math.radians(deg)
        tx, tz = round(40 * math.cos(rad)), round(40 * math.sin(rad))
        c.pillar(tx, tz, 2, 18, "quartz_pillar", 1)
        for y in range(19, 23):
            c.rel_put(tx, y, tz, "blue_ice")
        c.rel_put(tx, 23, tz, "sea_lantern")

    # 4 clean crystal pillars at r=21 for Potion PvP kite loops
    for dx, dz in ((-15, -15), (15, -15), (-15, 15), (15, 15)):
        c.pillar(dx, dz, 2, 6, "quartz_pillar", 1)
        c.rel_put(dx, 7, dz, "sea_lantern")

    c.add_spectator_box(-28, 10, "quartz_bricks", "prismarine_wall", "prismarine_bricks", "sea_lantern")
    return c


# ---------------------------------------------------------------------------
# Arena Metadata, Spawn Generation & Mathematical Clearance Verification
# ---------------------------------------------------------------------------

ARENA_SPECS = [
    ("nethpot", "NethPot - Nether Citadel", 0, 80, 0, build_nethpot, 30, 18, 10, -32, 12),
    ("axe-shield", "Axe & Shield - Ironkeep", 300, 80, 0, build_axe_shield, 24, 15, 8, -28, 11),
    ("build-uhc", "Build UHC - Verdant Basin", 600, 80, 0, build_build_uhc, 22, 14, 8, -28, 13),
    ("sword", "Sword - Grand Colosseum", 900, 80, 0, build_sword, 24, 15, 8, -28, 10),
    ("uhc", "UHC - Wild Frontier", 1200, 80, 0, build_uhc, 24, 15, 8, -31, 13),
    ("crystal", "Crystal - End Rift", 1600, 80, 0, build_crystal, 26, 16, 8, -31, 11),
    ("smp", "SMP - Fallen Kingdom", 1900, 80, 0, build_smp, 26, 16, 8, -31, 12),
    ("diamond-pot", "Diamond Pot - Frostglass Temple", 2200, 80, 0, build_diamond_pot, 24, 15, 8, -28, 11),
]


def verify_and_clear_spawn(c: VoxelCanvas, dx: int, dy: int, dz: int, label: str) -> None:
    """Guarantees solid non-hazard floor at dy-1 and pure air at dy, dy+1, dy+2."""
    below = c.rel_get(dx, dy - 1, dz)
    if below in ("minecraft:air", "minecraft:lava", "minecraft:water"):
        raise AssertionError(f"Unsafe floor ({below}) at {label} ({dx},{dy},{dz})")
    for check_y in (dy, dy + 1, dy + 2):
        blk = c.rel_get(dx, check_y, dz)
        if blk != "minecraft:air":
            raise AssertionError(f"Spawn blocked by {blk} at {label} ({dx},{check_y},{dz})")


def ring_spawns(px: int, py: int, pz: int, r: int, count: int, dy: int = 2) -> list[dict]:
    pts = []
    for i in range(count):
        ang = (2.0 * math.pi * i) / count
        dx = round(r * math.cos(ang))
        dz = round(r * math.sin(ang))
        # Face inward toward arena center
        yaw = round(math.degrees(math.atan2(-dx, -dz)), 1)
        pts.append({"dx": dx, "dy": dy, "dz": dz, "x": px + dx + 0.5, "y": py + dy, "z": pz + dz + 0.5, "yaw": yaw})
    return pts


def write_arenas_yml(entries: list[dict]) -> None:
    lines = ["arenas:"]
    for e in entries:
        lines.append(f"  {e['id']}:")
        lines.append(f"    display-name: {e['display']}")
        lines.append(f"    schematic: {e['id']}.schem")
        lines.append("    paste:")
        lines.append(f"      x: {e['px']}")
        lines.append(f"      y: {e['py']}")
        lines.append(f"      z: {e['pz']}")
        lines.append("    bounds:")
        for k in ("min-x", "min-y", "min-z", "max-x", "max-y", "max-z"):
            lines.append(f"      {k}: {e['bounds'][k]}")
        lines.append("    spawns:")
        for phase in ("wave1", "wave2", "final", "audience"):
            lines.append(f"      {phase}:")
            for sp in e["spawns"][phase]:
                lines.append(f"      - x: {sp['x']}")
                lines.append(f"        y: {sp['y']}")
                lines.append(f"        z: {sp['z']}")
                if "yaw" in sp:
                    lines.append(f"        yaw: {sp['yaw']}")
    with open(ARENAS_YML, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def main() -> None:
    for d in OUT_DIRS:
        os.makedirs(d, exist_ok=True)

    yml_entries = []
    for arena_id, display, px, py, pz, builder, r_w1, r_w2, r_fin, aud_dz, aud_dy in ARENA_SPECS:
        canvas = builder()

        w1 = ring_spawns(px, py, pz, r_w1, 8, dy=2)
        w2 = ring_spawns(px, py, pz, r_w2, 4, dy=2)
        fin = [
            {"dx": -r_fin, "dy": 2, "dz": 0, "x": px - r_fin + 0.5, "y": py + 2, "z": pz + 0.5, "yaw": -90.0},
            {"dx": r_fin, "dy": 2, "dz": 0, "x": px + r_fin + 0.5, "y": py + 2, "z": pz + 0.5, "yaw": 90.0},
        ]
        aud = [
            {"dx": 0, "dy": aud_dy, "dz": aud_dz, "x": px + 0.5, "y": py + aud_dy, "z": pz + aud_dz + 0.5, "yaw": 0.0}
        ]

        # Ensure every single spawn is 100% clear of pillars/trees/walls!
        for group_name, group in (("wave1", w1), ("wave2", w2), ("final", fin), ("audience", aud)):
            for idx, sp in enumerate(group):
                if group_name != "audience":
                    canvas.clear_cylinder(sp["dx"], sp["dz"], sp["dy"], sp["dy"] + 3, 1.5)
                verify_and_clear_spawn(canvas, sp["dx"], sp["dy"], sp["dz"], f"{arena_id}.{group_name}[{idx}]")

        payload = canvas.encode()
        for out_dir in OUT_DIRS:
            out_path = os.path.join(out_dir, f"{arena_id}.schem")
            with open(out_path, "wb") as f:
                f.write(payload)

        half_w = canvas.w // 2
        half_l = canvas.l // 2
        max_y = py + max(canvas.h + 12, 42 if arena_id == "build-uhc" else 34)
        yml_entries.append(
            {
                "id": arena_id,
                "display": display,
                "px": px,
                "py": py,
                "pz": pz,
                "bounds": {
                    "min-x": px - half_w - 2,
                    "min-y": py - 1,
                    "min-z": pz - half_l - 2,
                    "max-x": px + half_w + 2,
                    "max-y": max_y,
                    "max-z": pz + half_l + 2,
                },
                "spawns": {"wave1": w1, "wave2": w2, "final": fin, "audience": aud},
            }
        )
        print(f"OK {arena_id:12s} | {canvas.w}x{canvas.h}x{canvas.l} | palette={len(canvas.palette):2d} | {len(payload):6d} bytes | all 15 spawns verified")

    write_arenas_yml(yml_entries)
    print(f"Updated {ARENAS_YML}")


if __name__ == "__main__":
    main()

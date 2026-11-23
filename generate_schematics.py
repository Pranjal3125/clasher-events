#!/usr/bin/env python3
"""Generate 10 architectural-grade Sponge Schematic v2 builds for Clasher Events v2.1.

Includes:
  - 8 unique, tall (38-46 high), textured PvP arenas using directional stairs, slabs,
    vaulted arches, waterfalls/lavafalls, Perlin-style cliffs, sculpted trees, chandeliers,
    and multi-block weathered noise gradients
  - 1 Hypixel-style floating Sky Citadel Grand Event Spawn Lobby (with rock underside & parkour)
  - 1 Royal Champions Winner Podium & Stadium Crowd Gallery

All 123 spawns are mathematically verified (solid non-hazard floor below, clear air above).
Run: python generate_schematics.py
"""

from __future__ import annotations

import gzip
import math
import os
import struct

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT_DIRS = [
    os.path.join(ROOT, "schematics"),
    os.path.join(ROOT, "src", "main", "resources", "schematics"),
]
ARENAS_YML = os.path.join(ROOT, "src", "main", "resources", "arenas.yml")


# ---------------------------------------------------------------------------
# Deterministic Value Noise (for natural cliffs, stone weathering & organic trees)
# ---------------------------------------------------------------------------

def _hash2(ix: int, iz: int, seed: int = 0) -> float:
    n = (ix * 374761393 + iz * 668265263 + seed * 1442695041) & 0xFFFFFFFF
    n = (n ^ (n >> 13)) * 1274126177 & 0xFFFFFFFF
    n = n ^ (n >> 16)
    return (n & 0xFFFF) / 65535.0


def _smoothstep(t: float) -> float:
    return t * t * (3.0 - 2.0 * t)


def noise2(x: float, z: float, seed: int = 0) -> float:
    ix, iz = math.floor(x), math.floor(z)
    fx, fz = _smoothstep(x - ix), _smoothstep(z - iz)
    a = _hash2(ix, iz, seed)
    b = _hash2(ix + 1, iz, seed)
    c = _hash2(ix, iz + 1, seed)
    d = _hash2(ix + 1, iz + 1, seed)
    return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz


def fbm2(x: float, z: float, seed: int = 0, octaves: int = 3) -> float:
    val, amp, freq, norm = 0.0, 0.5, 1.0, 0.0
    for o in range(octaves):
        val += amp * noise2(x * freq, z * freq, seed + o * 31)
        norm += amp
        amp *= 0.5
        freq *= 2.05
    return val / norm


def pick_gradient(palette: list[str], x: int, y: int, z: int, seed: int = 7) -> str:
    n = fbm2(x * 0.17 + y * 0.11, z * 0.17 - y * 0.09, seed, 2)
    idx = min(len(palette) - 1, max(0, int(n * len(palette))))
    return palette[idx]


# ---------------------------------------------------------------------------
# Sponge Schematic v2 Binary NBT Encoder
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


# ---------------------------------------------------------------------------
# Architectural Voxel Canvas (supports full block states & vertical offsets!)
# ---------------------------------------------------------------------------

class VoxelCanvas:
    def __init__(self, width: int, height: int, length: int, y_origin: int = 0):
        self.w = width
        self.h = height
        self.l = length
        self.cx = width // 2
        self.cz = length // 2
        self.y_origin = y_origin  # Local grid Y that aligns with paste.y (allows floating underside at dy < 0!)
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

    def put_raw(self, x: int, y: int, z: int, block: str) -> None:
        if 0 <= x < self.w and 0 <= y < self.h and 0 <= z < self.l:
            self.grid[(y * self.l + z) * self.w + x] = self.block_id(block)

    def get_raw(self, x: int, y: int, z: int) -> str:
        if 0 <= x < self.w and 0 <= y < self.h and 0 <= z < self.l:
            return self.palette[self.grid[(y * self.l + z) * self.w + x]]
        return "minecraft:air"

    def rel_put(self, dx: int, dy: int, dz: int, block: str) -> None:
        self.put_raw(self.cx + dx, self.y_origin + dy, self.cz + dz, block)

    def rel_get(self, dx: int, dy: int, dz: int) -> str:
        return self.get_raw(self.cx + dx, self.y_origin + dy, self.cz + dz)

    def disk(self, r_outer: float, dy: int, block, r_inner: float = 0.0, ox: int = 0, oz: int = 0, seed: int = 1) -> None:
        ro2 = r_outer * r_outer
        ri2 = r_inner * r_inner
        ir = int(math.ceil(r_outer))
        for dx in range(ox - ir, ox + ir + 1):
            for dz in range(oz - ir, oz + ir + 1):
                d2 = (dx - ox) ** 2 + (dz - oz) ** 2
                if ri2 <= d2 <= ro2:
                    b = pick_gradient(block, dx, dy, dz, seed) if isinstance(block, list) else block
                    self.rel_put(dx, dy, dz, b)

    def fill_box(self, dx0: int, dy0: int, dz0: int, dx1: int, dy1: int, dz1: int, block, seed: int = 2) -> None:
        for dy in range(min(dy0, dy1), max(dy0, dy1) + 1):
            for dx in range(min(dx0, dx1), max(dx0, dx1) + 1):
                for dz in range(min(dz0, dz1), max(dz0, dz1) + 1):
                    b = pick_gradient(block, dx, dy, dz, seed) if isinstance(block, list) else block
                    self.rel_put(dx, dy, dz, b)

    def pillar(self, dx: int, dz: int, y0: int, y1: int, block, r: int = 1, seed: int = 3) -> None:
        self.fill_box(dx - r, y0, dz - r, dx + r, y1, dz + r, block, seed)

    def clear_cylinder(self, dx: int, dz: int, y0: int, y1: int, radius: float) -> None:
        r2 = radius * radius
        ir = int(math.ceil(radius))
        for dy in range(y0, y1 + 1):
            for ox in range(-ir, ir + 1):
                for oz in range(-ir, ir + 1):
                    if ox * ox + oz * oz <= r2:
                        self.rel_put(dx + ox, dy, dz + oz, "air")

    def add_floating_underside(self, radius: float, max_depth: int, rock_palette: list[str], seed: int = 19) -> None:
        """Sculpts a jagged Hypixel-style floating island rock underside tapering downward."""
        ir = int(math.ceil(radius))
        for dx in range(-ir, ir + 1):
            for dz in range(-ir, ir + 1):
                r = math.hypot(dx, dz)
                if r > radius:
                    continue
                t = max(0.0, 1.0 - (r / radius) ** 1.65)
                n = fbm2(dx * 0.13, dz * 0.13, seed, 3)
                depth = int(round(max_depth * t * (0.55 + 0.75 * n)))
                for d in range(1, depth + 1):
                    self.rel_put(dx, -d, dz, pick_gradient(rock_palette, dx, -d, dz, seed))

    def add_curved_arch(self, x0: int, y0: int, z0: int, x1: int, y1: int, z1: int, apex_y: int, block, thickness: int = 0) -> None:
        """Draws a smooth parabolic gothic/Roman structural rib arch between two 3D anchors."""
        steps = max(24, int(math.hypot(x1 - x0, z1 - z0) * 2.5))
        for i in range(steps + 1):
            t = i / steps
            px = round(x0 + (x1 - x0) * t)
            pz = round(z0 + (z1 - z0) * t)
            base_y = y0 + (y1 - y0) * t
            arch_lift = 4.0 * t * (1.0 - t) * (apex_y - base_y)
            py = round(base_y + arch_lift)
            for ox in range(-thickness, thickness + 1):
                for oz in range(-thickness, thickness + 1):
                    b = pick_gradient(block, px + ox, py, pz + oz, 11) if isinstance(block, list) else block
                    self.rel_put(px + ox, py, pz + oz, b)
                    self.rel_put(px + ox, py + 1, pz + oz, b)

    def add_hanging_chandelier(self, dx: int, dz: int, top_y: int, chain_len: int, core_blk: str, rim_blk: str, lantern_blk: str = "lantern[hanging=true]") -> None:
        for i in range(chain_len):
            self.rel_put(dx, top_y - i, dz, "iron_chain[axis=y]")
        cy = top_y - chain_len
        self.rel_put(dx, cy, dz, core_blk)
        for ox, oz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            self.rel_put(dx + ox, cy, dz + oz, rim_blk)
            self.rel_put(dx + ox, cy - 1, dz + oz, lantern_blk)
        self.rel_put(dx, cy - 1, dz, lantern_blk)

    def add_brazier(self, dx: int, dy: int, dz: int, base_blk: str, fire_blk: str = "campfire[lit=true]") -> None:
        for ox in (-1, 0, 1):
            for oz in (-1, 0, 1):
                if abs(ox) + abs(oz) <= 1:
                    self.rel_put(dx + ox, dy, dz + oz, base_blk)
        self.rel_put(dx, dy + 1, dz, fire_blk)

    def add_organic_tree(self, dx: int, dy: int, dz: int, log_blk: str, leaf_blk: str, height: int = 8, pine: bool = False, seed: int = 5) -> None:
        """Builds a sculpted multi-branch organic tree with persistent leaves."""
        leaf_state = leaf_blk if "[" in leaf_blk else f"{leaf_blk}[persistent=true]"
        # Root flare
        for ox, oz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            if _hash2(dx + ox, dz + oz, seed) > 0.35:
                self.rel_put(dx + ox, dy, dz + oz, log_blk)
        # Slightly organic trunk
        cx, cz = dx, dz
        for h in range(height):
            if not pine and h >= height // 2 and _hash2(dx, dz + h, seed) > 0.65:
                cx += 1 if _hash2(h, dx, seed) > 0.5 else -1
            self.rel_put(cx, dy + h, cz, log_blk)
            if pine and h >= 2:
                rad = max(1, int(round((height - h + 1) * 0.48)))
                for ox in range(-rad, rad + 1):
                    for oz in range(-rad, rad + 1):
                        if ox == 0 and oz == 0:
                            continue
                        dist = math.hypot(ox, oz)
                        if dist <= rad + 0.25 and (dist < rad - 0.3 or _hash2(cx + ox, cz + oz + h, seed) > 0.28):
                            self.rel_put(cx + ox, dy + h, cz + oz, leaf_state)
        if pine:
            self.rel_put(cx, dy + height, cz, leaf_state)
            self.rel_put(cx, dy + height + 1, cz, leaf_state)
        else:
            # Multi-cluster lush oak/birch canopy
            clusters = [(0, 0, 0, 3.0), (-2, -1, 1, 2.2), (2, -1, -1, 2.2), (1, 1, 2, 2.0), (-1, 1, -2, 2.0)]
            top_y = dy + height
            for bx, by, bz, rad in clusters:
                ir = int(math.ceil(rad))
                for ox in range(-ir, ir + 1):
                    for oy in range(-ir, ir + 1):
                        for oz in range(-ir, ir + 1):
                            if ox * ox + (oy * 1.35) ** 2 + oz * oz <= rad * rad:
                                tx, ty, tz = cx + bx + ox, top_y + by + oy, cz + bz + oz
                                if self.rel_get(tx, ty, tz) == "minecraft:air":
                                    self.rel_put(tx, ty, tz, leaf_state)

    def add_spectator_pavilion(
        self,
        z_center: int,
        y_floor: int,
        floor_pal: list[str],
        pillar_blk: str,
        rail_blk: str,
        roof_slab: str,
        light_blk: str,
    ) -> None:
        """Builds a grand arched, roofed spectator viewing box with columns and hanging lanterns."""
        # Solid deck + viewing rail
        for dx in range(-12, 13):
            for dz in range(z_center - 4, z_center + 5):
                self.rel_put(dx, y_floor, dz, pick_gradient(floor_pal, dx, y_floor, dz, 41))
                for cy in range(y_floor + 1, y_floor + 6):
                    self.rel_put(dx, cy, dz, "air")
                if abs(dx) == 12 or dz in (z_center - 4, z_center + 4):
                    self.rel_put(dx, y_floor + 1, dz, rail_blk)
        # Support columns reaching all the way down + canopy roof pillars
        for dx in (-12, -6, 0, 6, 12):
            for dz in (z_center - 4, z_center + 4):
                for y in range(1, y_floor):
                    self.rel_put(dx, y, dz, pillar_blk)
                for y in range(y_floor + 1, y_floor + 6):
                    if dz == z_center - 4 or abs(dx) in (6, 12):
                        self.rel_put(dx, y, dz, pillar_blk)
        # Stepped overhanging canopy roof
        for dx in range(-13, 14):
            for dz in range(z_center - 5, z_center + 6):
                edge = abs(dx) == 13 or dz in (z_center - 5, z_center + 5)
                self.rel_put(dx, y_floor + 6, dz, roof_slab if edge else pillar_blk)
                if not edge and abs(dx) <= 10 and z_center - 3 <= dz <= z_center + 3:
                    self.rel_put(dx, y_floor + 7, dz, roof_slab)
        # Hanging lanterns
        for dx in (-9, -3, 3, 9):
            self.rel_put(dx, y_floor + 5, z_center, light_blk)
        # Guarantee open sightline & clear spawn
        self.fill_box(-5, y_floor + 1, z_center - 2, 5, y_floor + 4, z_center + 2, "air")

    def encode(self) -> bytes:
        data = b"".join(varint(v) for v in self.grid)
        pal_tags = [int_tag(k, i) for i, k in enumerate(self.palette)]
        off_x, off_y, off_z = -self.cx, -self.y_origin, -self.cz
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
# 1. NethPot — Gothic Nether Citadel (128x44x128)
# ---------------------------------------------------------------------------

def build_nethpot() -> VoxelCanvas:
    c = VoxelCanvas(128, 44, 128)
    floor_pal = ["polished_blackstone_bricks", "polished_blackstone", "cracked_polished_blackstone_bricks", "blackstone"]
    wall_pal = ["blackstone", "polished_blackstone_bricks", "basalt", "deepslate_bricks", "cracked_polished_blackstone_bricks"]

    # Solid subfloor + rich textured combat deck at y=1
    c.disk(43, 0, "obsidian")
    c.disk(42, 1, floor_pal, seed=11)
    c.disk(30, 1, ["polished_blackstone", "polished_blackstone_bricks", "deepslate_tiles"], seed=12)
    # Glowing magma rune mosaic beneath flush red/black stained glass (100% walk-safe, no burn!)
    c.disk(9, 0, "magma_block")
    c.disk(8, 1, "red_stained_glass")
    c.disk(5, 1, "black_stained_glass")
    c.disk(3, 1, "gilded_blackstone")

    # Flush red nether brick & gilded cross roads
    for t in range(-40, 41):
        for w in (-1, 0, 1):
            for dx, dz in ((t, w), (w, t)):
                if dx * dx + dz * dz <= 41 * 41 and abs(t) >= 9:
                    blk = "gilded_blackstone" if (w == 0 and abs(t) % 5 == 0) else ("red_nether_bricks" if w == 0 else "nether_bricks")
                    c.rel_put(dx, 1, dz, blk)

    # Boiling lava moat + obsidian rim
    c.disk(52, 0, "obsidian", 43)
    c.disk(49, 1, "lava", 44)
    c.disk(44, 1, "polished_blackstone_bricks", 43)
    c.disk(51, 1, "magma_block", 49)

    # 4 Grand vaulted gothic bridges over the lava moat
    for t in range(42, 56):
        for w in range(-5, 6):
            for dx, dz in ((w, -t), (w, t), (-t, w), (t, w)):
                c.rel_put(dx, 1, dz, "red_nether_bricks" if abs(w) <= 1 else "polished_blackstone_bricks")
                if abs(w) == 5:
                    c.rel_put(dx, 2, dz, "polished_blackstone_brick_wall")
                    if t % 4 == 0:
                        c.rel_put(dx, 3, dz, "nether_brick_fence")
                        c.rel_put(dx, 4, dz, "soul_lantern")

    # High textured gothic curtain wall (y=0..20) with recessed crimson-glass lancet arches & lavafalls!
    for y in range(0, 20):
        c.disk(61, y, wall_pal, 54, seed=15)
        if y in (6, 13, 19):
            c.disk(55, y, "red_nether_bricks", 53)
    # Battlements with alternating slabs & walls
    for deg in range(0, 360, 4):
        rad = math.radians(deg)
        dx, dz = round(57.5 * math.cos(rad)), round(57.5 * math.sin(rad))
        c.rel_put(dx, 20, dz, "polished_blackstone_brick_slab[type=bottom]" if deg % 8 == 0 else "polished_blackstone_bricks")
        if deg % 8 == 4:
            c.rel_put(dx, 21, dz, "blackstone_wall")

    # 8 Cascading Lavafalls pouring from elevated basalt spouts down into the moat!
    for deg in range(22, 360, 45):
        rad = math.radians(deg)
        for step in range(49, 55):
            lx, lz = round(step * math.cos(rad)), round(step * math.sin(rad))
            c.rel_put(lx, 17, lz, "obsidian")
            c.rel_put(lx, 18, lz, "lava" if step >= 50 else "air")
        fx, fz = round(48.5 * math.cos(rad)), round(48.5 * math.sin(rad))
        for y in range(2, 19):
            c.rel_put(fx, y, fz, "lava")

    # 8 Towering Gothic Bastion Spires (height 39!) with stepped spire roofs & hanging chain braziers
    for deg in range(0, 360, 45):
        rad = math.radians(deg)
        tx, tz = round(55 * math.cos(rad)), round(55 * math.sin(rad))
        for y in range(0, 28):
            for ox in range(-6, 7):
                for oz in range(-6, 7):
                    edge = max(abs(ox), abs(oz))
                    if y <= 2:
                        c.rel_put(tx + ox, y, tz + oz, "polished_blackstone_bricks")
                    elif edge == 6:
                        if abs(ox) <= 1 and 8 <= y <= 20 and abs(oz) == 6:
                            c.rel_put(tx + ox, y, tz + oz, "red_stained_glass")
                        elif abs(oz) <= 1 and 8 <= y <= 20 and abs(ox) == 6:
                            c.rel_put(tx + ox, y, tz + oz, "red_stained_glass")
                        else:
                            c.rel_put(tx + ox, y, tz + oz, pick_gradient(wall_pal, tx + ox, y, tz + oz, 19))
                    elif edge == 5 and y in (12, 22, 27):
                        c.rel_put(tx + ox, y, tz + oz, "red_nether_bricks")
        # Tapered gothic spire crown (y=28..39)
        for step, rad_s in enumerate((6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1, 0)):
            yy = 28 + step
            for ox in range(-rad_s, rad_s + 1):
                for oz in range(-rad_s, rad_s + 1):
                    if max(abs(ox), abs(oz)) == rad_s:
                        c.rel_put(tx + ox, yy, tz + oz, "red_nether_bricks" if step % 2 == 0 else "obsidian")
        c.rel_put(tx, 40, tz, "shroomlight")
        # Flying buttress arch leaping inward from each tower!
        ix, iz = round(43 * math.cos(rad)), round(43 * math.sin(rad))
        c.add_curved_arch(tx, 20, tz, ix, 2, iz, 25, ["red_nether_bricks", "polished_blackstone_bricks"], 0)
        # Hanging chandelier beneath each arch
        hx, hz = round(47 * math.cos(rad)), round(47 * math.sin(rad))
        c.add_hanging_chandelier(hx, hz, 22, 4, "shroomlight", "gilded_blackstone", "soul_lantern[hanging=true]")

    # 4 Subtle sculpted cover monoliths at r=24 with soul-fire braziers (wide open PvP!)
    for deg in (45, 135, 225, 315):
        rad = math.radians(deg)
        px, pz = round(24 * math.cos(rad)), round(24 * math.sin(rad))
        c.pillar(px, pz, 2, 5, wall_pal, 1, seed=29)
        c.add_brazier(px, 6, pz, "polished_blackstone_brick_slab[type=bottom]", "soul_campfire[lit=true]")

    c.add_spectator_pavilion(-32, 11, ["nether_bricks", "polished_blackstone_bricks"], "polished_blackstone_bricks", "blackstone_wall", "red_nether_brick_slab[type=bottom]", "soul_lantern[hanging=true]")
    return c


# ---------------------------------------------------------------------------
# 2. Sword — Grand 3-Tier Roman Colosseum (112x44x112)
# ---------------------------------------------------------------------------

def build_sword() -> VoxelCanvas:
    c = VoxelCanvas(112, 44, 112)
    sand_pal = ["smooth_sandstone", "sandstone", "cut_sandstone", "stripped_birch_wood"]
    marble_pal = ["quartz_bricks", "smooth_quartz", "chiseled_quartz_block", "smooth_sandstone"]

    # Warm Roman arena sand floor (flat, textured, high-visibility!)
    c.disk(38, 0, "smooth_sandstone")
    c.disk(36, 1, sand_pal, seed=21)
    c.disk(12, 1, ["cut_sandstone", "smooth_sandstone"])
    c.disk(7, 1, "quartz_bricks")
    c.disk(4, 1, "gold_block")
    c.disk(2, 1, "sea_lantern")

    # Flush marble cross lanes
    for t in range(-35, 36):
        c.rel_put(t, 1, 0, "quartz_bricks")
        c.rel_put(0, 1, t, "quartz_bricks")

    # Concentric sloping Roman amphitheater spectator bleachers (r=36..44, y=2..10)!
    for tier in range(8):
        r_in = 36 + tier
        r_out = r_in + 1.4
        c.disk(r_out, 2 + tier, marble_pal if tier % 2 == 0 else sand_pal, r_in - 0.3, seed=23)
        # Solid support under bleachers
        for y in range(1, 2 + tier):
            c.disk(r_out, y, "smooth_sandstone", r_in - 0.1)

    # 3 Stacked Tiers of REAL Roman Recessed Arched Colosseum Facade (r=44..52, y=10..36)!
    for y in range(10, 36):
        c.disk(52, y, marble_pal, 44.5, seed=25)
    # Carve 24 dramatic Roman arch windows on all 3 tiers (y=12..17, 20..25, 28..33)
    for deg in range(0, 360, 15):
        rad = math.radians(deg)
        for tier_y in (12, 20, 28):
            for r_step in range(44, 53):
                for spread in (-1, 0, 1):
                    ang = rad + math.radians(spread * 1.9)
                    ax, az = round(r_step * math.cos(ang)), round(r_step * math.sin(ang))
                    max_h = 5 if spread == 0 else 4
                    for dy in range(max_h):
                        c.rel_put(ax, tier_y + dy, az, "air")
            # Cornice ledge between stories
            for r_step in range(44, 54):
                lx, lz = round(r_step * math.cos(rad)), round(r_step * math.sin(rad))
                c.rel_put(lx, tier_y - 1, lz, "quartz_pillar")
                c.rel_put(lx, tier_y + 5, lz, "chiseled_quartz_block")

    # Iconic Red & Gold Roman Velarium Sun-Sail Awning Ribs arching inward at y=36..40!
    for deg in range(0, 360, 15):
        rad = math.radians(deg)
        for s in range(0, 11):
            r_cur = 51 - s * 1.1
            yy = 36 + int(s * 0.35)
            for sw in (-1, 0, 1):
                ang = rad + math.radians(sw * 2.2)
                vx, vz = round(r_cur * math.cos(ang)), round(r_cur * math.sin(ang))
                if s == 0:
                    c.rel_put(vx, 36, vz, "oak_log")
                    c.rel_put(vx, 37, vz, "oak_log")
                else:
                    blk = "gold_block" if sw == 0 and s % 3 == 0 else ("red_wool" if (deg // 15) % 2 == 0 else "white_wool")
                    c.rel_put(vx, yy, vz, blk)

    # 4 Grand Arched Gladiator Entrance Portcullis Gates with burning braziers
    for axis in ("x", "z"):
        for sign in (-1, 1):
            for t in range(35, 45):
                for w in range(-3, 4):
                    for y in range(2, 9):
                        dx, dz = (sign * t, w) if axis == "x" else (w, sign * t)
                        if abs(w) == 3 or (y >= 7 and abs(w) + y >= 10):
                            c.rel_put(dx, y, dz, "quartz_pillar")
                        else:
                            c.rel_put(dx, y, dz, "iron_bars" if (t == 39 and y >= 4) else "air")
            bx, bz = (sign * 34, 4) if axis == "x" else (4, sign * 34)
            c.add_brazier(bx, 2, bz, "cut_sandstone", "campfire[lit=true]")
            bx2, bz2 = (sign * 34, -4) if axis == "x" else (-4, sign * 34)
            c.add_brazier(bx2, 2, bz2, "cut_sandstone", "campfire[lit=true]")

    # 4 Classical Quartz Columns at r=21 for Sword spacing
    for dx, dz in ((-15, -15), (15, -15), (-15, 15), (15, 15)):
        c.pillar(dx, dz, 2, 7, "quartz_pillar", 1)
        c.rel_put(dx, 8, dz, "gold_block")
        c.rel_put(dx, 9, dz, "sea_lantern")

    # Imperial Emperor's Box on North side
    c.add_spectator_pavilion(-28, 10, ["quartz_bricks", "red_wool"], "quartz_pillar", "sandstone_wall", "quartz_slab[type=bottom]", "lantern[hanging=true]")
    return c


# ---------------------------------------------------------------------------
# 3. Crystal PvP — End Rift Shattered Spire Arena (112x42x112)
# ---------------------------------------------------------------------------

def build_crystal() -> VoxelCanvas:
    c = VoxelCanvas(112, 42, 112)
    end_pal = ["end_stone_bricks", "end_stone", "purpur_block", "obsidian"]

    # Blast-proof bedrock foundation + textured obsidian/end-stone Crystal PvP deck
    c.disk(43, 0, "bedrock")
    c.disk(41, 1, ["end_stone_bricks", "end_stone_bricks", "end_stone", "obsidian"], seed=31)
    # Flush obsidian Crystal PvP combat grid rings & axes
    c.disk(34, 1, "obsidian", 32.5)
    c.disk(18, 1, "obsidian", 16.5)
    c.disk(6, 1, "crying_obsidian")
    c.disk(3, 1, "amethyst_block")
    for t in range(-38, 39):
        for off in (-14, 0, 14):
            if t * t + off * off <= 39 * 39:
                c.rel_put(t, 1, off, "obsidian")
                c.rel_put(off, 1, t, "obsidian")

    # Jagged Obsidian & Purpur Crater Rim (y=1..18)
    for dx in range(-52, 53):
        for dz in range(-52, 53):
            r = math.hypot(dx, dz)
            if 40.5 <= r <= 50.5:
                n = fbm2(dx * 0.14, dz * 0.14, 33, 3)
                wh = int(10 + 11 * n)
                for y in range(1, wh + 1):
                    blk = "obsidian" if y < 6 else pick_gradient(["obsidian", "crying_obsidian", "purpur_block", "end_stone_bricks"], dx, y, dz, 34)
                    c.rel_put(dx, y, dz, blk)

    # 8 Giant Diagonal Amethyst & Obsidian Crystal Spikes jutting inward over the rim (y=12..36)!
    for deg in range(0, 360, 45):
        rad = math.radians(deg)
        for step in range(26):
            r_cur = 49.0 - step * 0.65
            yy = 10 + step
            thick = max(0, 3 - step // 7)
            cx_s, cz_s = round(r_cur * math.cos(rad)), round(r_cur * math.sin(rad))
            for ox in range(-thick, thick + 1):
                for oz in range(-thick, thick + 1):
                    if abs(ox) + abs(oz) <= thick + 1:
                        blk = "amethyst_block" if step > 14 else ("crying_obsidian" if (step + ox) % 3 == 0 else "obsidian")
                        c.rel_put(cx_s + ox, yy, cz_s + oz, blk)
            if step == 25:
                c.rel_put(cx_s, yy + 1, cz_s, "sea_lantern")

    # 6 Floating Shattered End Islands drifting high above the arena connected by glowing beams!
    for deg in range(30, 360, 60):
        rad = math.radians(deg)
        ix, iz = round(38 * math.cos(rad)), round(38 * math.sin(rad))
        iy = 28 + (deg // 60) % 2 * 5
        for ox in range(-4, 5):
            for oz in range(-4, 5):
                d = math.hypot(ox, oz)
                if d <= 4.2:
                    c.rel_put(ix + ox, iy, iz + oz, "end_stone_bricks" if d < 3 else "purpur_block")
                    depth = int(round((4.2 - d) * 1.4))
                    for dep in range(1, depth + 1):
                        c.rel_put(ix + ox, iy - dep, iz + oz, "obsidian" if dep == depth else "end_stone")
        c.rel_put(ix, iy + 1, iz, "amethyst_block")
        c.rel_put(ix, iy + 2, iz, "end_rod[facing=up]")
        # Glowing purple stained-glass energy beam toward center sky
        for s in range(1, 7):
            bx = round(ix - s * 1.6 * math.cos(rad))
            bz = round(iz - s * 1.6 * math.sin(rad))
            c.rel_put(bx, iy + 1, bz, "purple_stained_glass" if s % 2 else "magenta_stained_glass")

    # 4 Clean low obsidian Crystal anchor cover plinths at r=22
    for dx, dz in ((-16, -16), (16, -16), (-16, 16), (16, 16)):
        c.fill_box(dx - 1, 2, dz - 1, dx + 1, 3, dz + 1, "obsidian")
        c.rel_put(dx, 4, dz, "crying_obsidian")

    c.add_spectator_pavilion(-31, 11, ["purpur_block", "obsidian"], "purpur_pillar", "end_stone_brick_wall", "purpur_slab[type=bottom]", "end_rod[facing=down]")
    return c


# ---------------------------------------------------------------------------
# 4. Build UHC — Sculpted Mountain Valley & Waterfalls (112x42x112)
# ---------------------------------------------------------------------------

def build_build_uhc() -> VoxelCanvas:
    c = VoxelCanvas(112, 42, 112)
    cliff_pal = ["deepslate", "tuff", "stone", "andesite", "cobblestone", "mossy_cobblestone"]

    c.disk(52, 0, "bedrock")
    c.disk(50, 1, "dirt")
    # Lush natural textured meadow floor at y=1
    for dx in range(-40, 41):
        for dz in range(-40, 41):
            r = math.hypot(dx, dz)
            if r > 39:
                continue
            n = fbm2(dx * 0.14, dz * 0.14, 41, 3)
            blk = "moss_block" if n > 0.68 else ("coarse_dirt" if n < 0.25 else "grass_block")
            c.rel_put(dx, 1, dz, blk)
            # Subtle scattered wildflowers / short grass away from spawn pads
            if 6 < r < 34 and _hash2(dx, dz, 49) > 0.94:
                c.rel_put(dx, 2, dz, "short_grass" if _hash2(dx, dz, 50) > 0.35 else "poppy")

    # Winding creek along z=0 with arched oak & stone-slab footbridges over all spawn crossings!
    for dx in range(-36, 37):
        z_curve = round(2.2 * math.sin(dx * 0.14))
        for wz in (-1, 0, 1):
            if abs(dx) <= 6 or 12 <= abs(dx) <= 17 or 20 <= abs(dx) <= 25:
                continue
            c.rel_put(dx, 1, z_curve + wz, "water" if wz == 0 else "gravel")
            c.rel_put(dx, 2, z_curve + wz, "air")
    # Hand-crafted arched central wooden bridge!
    for dx in range(-6, 7):
        for dz in range(-3, 4):
            c.rel_put(dx, 1, dz, "oak_planks" if abs(dz) <= 2 else "stripped_oak_log")
            c.rel_put(dx, 2, dz, "oak_fence" if (abs(dz) == 3 and abs(dx) % 2 == 0) else "air")
            if abs(dz) == 3 and abs(dx) == 5:
                c.rel_put(dx, 3, dz, "lantern")

    # Dramatic Multi-Octave Perlin-Style Mountain Cliff Amphitheater (r=38..53, height 34!)
    for dx in range(-54, 55):
        for dz in range(-54, 55):
            r = math.hypot(dx, dz)
            if 38.0 <= r <= 53.5:
                ridge = fbm2(dx * 0.08, dz * 0.08, 43, 3)
                slope = min(1.0, (r - 37.5) / 8.0)
                taper = min(1.0, (54.0 - r) / 5.0)
                wh = int(round((14 + 22 * ridge) * slope * max(0.35, taper)))
                for y in range(1, wh + 1):
                    if y == wh:
                        blk = "grass_block" if wh < 26 else "stone"
                    elif y >= wh - 2 and wh < 26:
                        blk = "dirt"
                    elif y < 7:
                        blk = "deepslate"
                    else:
                        blk = pick_gradient(cliff_pal, dx, y, dz, 44)
                    c.rel_put(dx, y, dz, blk)

    # 2 Cascading Waterfalls pouring down East & West cliffs into rocky ponds!
    for sign in (-1, 1):
        wx = sign * 42
        for y in range(2, 24):
            for wz in (-1, 0, 1):
                c.rel_put(wx - sign * 2, y, wz, "water")
                c.rel_put(wx - sign * 3, y, wz, "air")
        c.disk(4.5, 1, "water", ox=sign * 36, oz=0)
        c.disk(5.5, 1, "cobblestone", 4.4, ox=sign * 36, oz=0)

    # Sculpted Multi-Branch Organic Oak & Birch Trees around the valley perimeter!
    tree_spots = [
        (-28, -24, "oak_log", "oak_leaves", 7), (28, -24, "birch_log", "birch_leaves", 7),
        (-28, 24, "birch_log", "birch_leaves", 8), (28, 24, "oak_log", "oak_leaves", 8),
        (-14, -31, "oak_log", "oak_leaves", 7), (14, 31, "oak_log", "oak_leaves", 7),
        (33, -12, "oak_log", "oak_leaves", 6), (-33, 12, "birch_log", "birch_leaves", 6),
    ]
    for i, (tx, tz, log_b, leaf_b, th) in enumerate(tree_spots):
        c.add_organic_tree(tx, 2, tz, log_b, leaf_b, height=th, pine=False, seed=60 + i)

    # Natural mossy boulders & warm campfires
    for bx, bz in ((-16, 18), (16, -18), (18, 16), (-18, -16)):
        c.fill_box(bx - 1, 2, bz - 1, bx + 1, 3, bz + 1, ["cobblestone", "mossy_cobblestone", "andesite"], seed=67)
        c.rel_put(bx, 4, bz, "moss_carpet")

    c.add_spectator_pavilion(-29, 13, ["oak_planks", "spruce_planks"], "stripped_oak_log", "oak_fence", "spruce_slab[type=bottom]", "lantern[hanging=true]")
    return c


# ---------------------------------------------------------------------------
# 5. Axe & Shield — Gothic Ironkeep Castle (108x42x108)
# ---------------------------------------------------------------------------

def build_axe_shield() -> VoxelCanvas:
    c = VoxelCanvas(108, 42, 108)
    stone_pal = ["stone_bricks", "cracked_stone_bricks", "mossy_stone_bricks", "andesite", "polished_andesite"]
    keep_pal = ["deepslate_bricks", "deepslate_tiles", "tuff_bricks", "stone_bricks"]

    c.disk(40, 0, "deepslate_bricks")
    c.disk(38, 1, stone_pal, seed=51)
    c.disk(12, 1, ["polished_tuff", "tuff_bricks"])
    c.disk(4, 1, "iron_block")

    # Flush cobblestone/deepslate courtyard avenues
    for t in range(-36, 37):
        for w in (-1, 0, 1):
            c.rel_put(t, 1, w, "deepslate_tiles" if w == 0 else "deepslate_bricks")
            c.rel_put(w, 1, t, "deepslate_tiles" if w == 0 else "deepslate_bricks")

    # High Fortified Castle Curtain Wall (y=1..20) with Tudor timber overhang & battlements!
    for y in range(1, 20):
        c.disk(47, y, keep_pal if y < 14 else ["oak_planks", "stripped_dark_oak_log", "white_terracotta"], 38.5, seed=53)
        if y == 14:
            c.disk(48, y, "dark_oak_planks", 37.5)
    for deg in range(0, 360, 6):
        rad = math.radians(deg)
        dx, dz = round(42.5 * math.cos(rad)), round(42.5 * math.sin(rad))
        c.rel_put(dx, 20, dz, "deepslate_brick_slab[type=bottom]" if deg % 12 == 0 else "deepslate_bricks")
        if deg % 12 == 6:
            c.rel_put(dx, 21, dz, "deepslate_brick_wall")

    # 4 Massive Gothic Castle Corner Towers (height 38!) with steep pitched dark-oak & deepslate roofs!
    for tx, tz in ((-33, -33), (33, -33), (-33, 33), (33, 33)):
        for y in range(1, 27):
            for ox in range(-6, 7):
                for oz in range(-6, 7):
                    edge = max(abs(ox), abs(oz))
                    if edge == 6:
                        is_win = (abs(ox) <= 1 or abs(oz) <= 1) and y in (10, 11, 12, 18, 19, 20)
                        c.rel_put(tx + ox, y, tz + oz, "iron_bars" if is_win else pick_gradient(keep_pal, tx + ox, y, tz + oz, 55))
                    elif edge == 5 and y in (14, 26):
                        c.rel_put(tx + ox, y, tz + oz, "dark_oak_planks")
        # Steep conical pitched spire roof (y=27..38)
        for step in range(11):
            rad_r = max(0, 7 - step * 0.68)
            yy = 27 + step
            ir = int(math.ceil(rad_r))
            for ox in range(-ir, ir + 1):
                for oz in range(-ir, ir + 1):
                    d = math.hypot(ox, oz)
                    if rad_r - 1.3 <= d <= rad_r + 0.3:
                        c.rel_put(tx + ox, yy, tz + oz, "deepslate_tiles" if step % 2 == 0 else "dark_oak_planks")
        c.rel_put(tx, 38, tz, "iron_block")
        c.rel_put(tx, 39, tz, "lantern")

    # 4 Timber-Framed Medieval Cover Bastions with Hanging Lanterns at r=21
    for dx, dz in ((-15, -15), (15, -15), (-15, 15), (15, 15)):
        c.pillar(dx, dz, 2, 6, keep_pal, 1, seed=58)
        c.rel_put(dx, 7, dz, "deepslate_tile_slab[type=bottom]")
        c.add_brazier(dx, 7, dz, "deepslate_bricks", "campfire[lit=true]")

    c.add_spectator_pavilion(-29, 11, ["dark_oak_planks", "oak_planks"], "stripped_dark_oak_log", "deepslate_brick_wall", "deepslate_tile_slab[type=bottom]", "lantern[hanging=true]")
    return c


# ---------------------------------------------------------------------------
# 6. UHC — Snow-Capped Alpine Frontier (112x44x112)
# ---------------------------------------------------------------------------

def build_uhc() -> VoxelCanvas:
    c = VoxelCanvas(112, 44, 112)
    c.disk(52, 0, "bedrock")
    for dx in range(-40, 41):
        for dz in range(-40, 41):
            r = math.hypot(dx, dz)
            if r > 39:
                continue
            n = fbm2(dx * 0.13, dz * 0.13, 61, 3)
            blk = "podzol" if n < 0.34 else ("coarse_dirt" if n < 0.50 else ("moss_block" if n > 0.78 else "grass_block"))
            if r <= 7:
                blk = pick_gradient(["cobblestone", "mossy_cobblestone", "coarse_dirt"], dx, 1, dz, 62)
            c.rel_put(dx, 1, dz, blk)

    # Tall Snow-Capped Alpine Mountain Ring (height 39!)
    for dx in range(-54, 55):
        for dz in range(-54, 55):
            r = math.hypot(dx, dz)
            if 38.5 <= r <= 53.5:
                peak = fbm2(dx * 0.085, dz * 0.085, 65, 3)
                slope = min(1.0, (r - 38.0) / 8.5)
                taper = min(1.0, (54.0 - r) / 4.5)
                wh = int(round((16 + 24 * peak) * slope * max(0.35, taper)))
                for y in range(1, wh + 1):
                    if y >= wh - 3 and wh >= 24:
                        blk = "snow_block" if y == wh else "calcite"
                    elif y == wh:
                        blk = "podzol"
                    else:
                        blk = pick_gradient(["deepslate", "tuff", "stone", "andesite"], dx, y, dz, 66)
                    c.rel_put(dx, y, dz, blk)

    # Dramatic Frozen Waterfall on the South Mountain Face!
    for y in range(2, 26):
        for ox in (-2, -1, 0, 1, 2):
            if abs(ox) <= 1 or y % 2 == 0:
                c.rel_put(ox, y, 40, "packed_ice" if abs(ox) == 1 else "blue_ice")

    # Dense Alpine Spruce Pine Forest around the outer ring!
    for i, deg in enumerate(range(15, 360, 24)):
        rad = math.radians(deg)
        tx, tz = round(32 * math.cos(rad)), round(32 * math.sin(rad))
        c.add_organic_tree(tx, 2, tz, "spruce_log", "spruce_leaves", height=9 + (i % 4), pine=True, seed=70 + i)

    # Warm Nordic Campfire Outposts at mid-ring
    for dx, dz in ((-17, -17), (17, -17), (-17, 17), (17, 17)):
        c.pillar(dx, dz, 2, 5, "spruce_log", 0)
        c.rel_put(dx, 6, dz, "spruce_slab[type=bottom]")
        c.add_brazier(dx + (1 if dx < 0 else -1), 1, dz, "cobblestone", "campfire[lit=true]")

    c.add_spectator_pavilion(-31, 13, ["spruce_planks", "stripped_spruce_log"], "spruce_log", "spruce_fence", "spruce_slab[type=bottom]", "lantern[hanging=true]")
    return c


# ---------------------------------------------------------------------------
# 7. SMP — Overgrown Ruined Cathedral & Team Wars Citadel (112x42x112)
# ---------------------------------------------------------------------------

def build_smp() -> VoxelCanvas:
    c = VoxelCanvas(112, 42, 112)
    ruin_pal = ["stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "cobblestone", "mossy_cobblestone"]

    c.disk(42, 0, "cobblestone")
    c.disk(40, 1, ruin_pal, seed=71)

    # Flush Red vs Blue Team Wars Carpet Lanes + Central Ruined Crest
    for dx in range(-37, -7):
        for dz in (-2, -1, 0, 1, 2):
            c.rel_put(dx, 1, dz, "red_terracotta" if abs(dz) <= 1 else "polished_andesite")
    for dx in range(8, 38):
        for dz in (-2, -1, 0, 1, 2):
            c.rel_put(dx, 1, dz, "blue_terracotta" if abs(dz) <= 1 else "polished_andesite")
    c.disk(7, 1, ["chiseled_stone_bricks", "mossy_stone_bricks"])
    c.disk(3, 1, "gold_block")

    # Weathered Ruined Cathedral Perimeter Walls (varying shattered skyline height 14..32!)
    for dx in range(-52, 53):
        for dz in range(-52, 53):
            r = math.hypot(dx, dz)
            if 40.0 <= r <= 49.0:
                n = fbm2(dx * 0.09, dz * 0.09, 73, 3)
                wh = int(12 + 19 * n)
                for y in range(1, wh + 1):
                    blk = "moss_block" if (y == wh and n > 0.55) else pick_gradient(ruin_pal, dx, y, dz, 74)
                    c.rel_put(dx, y, dz, blk)

    # Giant Shattered Stained-Glass Cathedral Rose Window on the South Nave (z=42, y=10..28)!
    for ox in range(-8, 9):
        for oy in range(-8, 9):
            d = math.hypot(ox, oy)
            if d <= 8.5:
                if d >= 7.2:
                    blk = "deepslate_bricks"
                elif abs(ox) <= 1 or abs(oy) <= 1:
                    blk = "stone_bricks"
                else:
                    blk = "red_stained_glass" if ox < 0 else "blue_stained_glass"
                    if d < 3.0:
                        blk = "yellow_stained_glass"
                c.fill_box(ox, 19 + oy, 40, ox, 19 + oy, 46, blk)

    # Dramatic Half-Collapsed Gothic Rib Arches leaping overhead + hanging moss!
    for z_arch in (-22, 0, 22):
        c.add_curved_arch(-41, 16, z_arch, 41, 16, z_arch, 37, ruin_pal, 1)
        # Break the middle section on the central arch for authentic ruined feel!
        if z_arch == 0:
            c.clear_cylinder(0, 0, 32, 41, 9.0)
        # Hanging lantern chandeliers & moss tendrils
        c.add_hanging_chandelier(-22, z_arch, 31, 5, "glowstone", "mossy_stone_bricks", "lantern[hanging=true]")
        c.add_hanging_chandelier(22, z_arch, 31, 5, "glowstone", "mossy_stone_bricks", "lantern[hanging=true]")

    # Massive Red & Blue War Banners flanking West & East walls
    for sign, banner_blk in ((-1, "red_wool"), (1, "blue_wool")):
        for bz in (-10, 10):
            for y in range(6, 18):
                for bw in (-1, 0, 1):
                    c.rel_put(sign * 39, y, bz + bw, "gold_block" if y == 17 else banner_blk)

    # Overgrown mid-field ruined pillars
    for dx, dz in ((-18, -15), (-18, 15), (18, -15), (18, 15)):
        c.pillar(dx, dz, 2, 7, ruin_pal, 1, seed=79)
        c.rel_put(dx, 8, dz, "lantern")

    c.add_spectator_pavilion(-31, 12, ["dark_oak_planks", "mossy_stone_bricks"], "dark_oak_log", "mossy_stone_brick_wall", "dark_oak_slab[type=bottom]", "lantern[hanging=true]")
    return c


# ---------------------------------------------------------------------------
# 8. Diamond Pot — Ethereal Frostglass Ice Cathedral (108x42x108) — NON-SLIPPERY!
# ---------------------------------------------------------------------------

def build_diamond_pot() -> VoxelCanvas:
    c = VoxelCanvas(108, 42, 108)
    # 100% NON-SLIPPERY frosted marble/prismarine/diorite floor (zero ice on walkable deck!)
    floor_pal = ["quartz_bricks", "polished_diorite", "smooth_quartz", "prismarine_bricks"]

    c.disk(39, 0, "prismarine_bricks")
    c.disk(37, 1, floor_pal, seed=81)
    c.disk(20, 1, ["quartz_bricks", "smooth_quartz", "calcite"], seed=82)
    c.disk(9, 1, "prismarine_bricks")
    c.disk(5, 1, "diamond_block")
    c.disk(2, 1, "sea_lantern")

    # Crisp high-contrast cyan terracotta compass inlay
    for t in range(-36, 37):
        c.rel_put(t, 1, 0, "cyan_terracotta")
        c.rel_put(0, 1, t, "cyan_terracotta")

    # Towering Translucent Ice & Cyan Stained-Glass Cathedral Walls (y=1..24)!
    for y in range(1, 24):
        c.disk(48, y, ["prismarine_bricks", "quartz_bricks", "packed_ice"], 39.5, seed=84)
    # Carve luminous cyan & light-blue gothic cathedral windows around all 16 bays!
    for deg in range(0, 360, 22):
        rad = math.radians(deg)
        for r_step in range(39, 49):
            for spread in (-1, 0, 1):
                ang = rad + math.radians(spread * 2.1)
                gx, gz = round(r_step * math.cos(ang)), round(r_step * math.sin(ang))
                for yy in range(5, 20 if spread == 0 else 17):
                    c.rel_put(gx, yy, gz, "light_blue_stained_glass" if (yy + spread) % 2 == 0 else "cyan_stained_glass")

    # 8 Soaring Crystalline Blue-Ice & Quartz Cathedral Spires (height 39!) + Glowing Ribs!
    for deg in range(0, 360, 45):
        rad = math.radians(deg)
        tx, tz = round(43 * math.cos(rad)), round(43 * math.sin(rad))
        c.pillar(tx, tz, 2, 26, ["quartz_pillar", "prismarine_bricks", "packed_ice"], 2, seed=86)
        for step in range(13):
            rad_s = max(0, 3 - step // 4)
            yy = 26 + step
            for ox in range(-rad_s, rad_s + 1):
                for oz in range(-rad_s, rad_s + 1):
                    if abs(ox) + abs(oz) <= rad_s + 1:
                        c.rel_put(tx + ox, yy, tz + oz, "blue_ice" if step % 2 == 0 else "light_blue_stained_glass")
        c.rel_put(tx, 39, tz, "sea_lantern")
        c.rel_put(tx, 40, tz, "end_rod[facing=up]")

        # Soaring frosted crystal arch rib arching gracefully inward + suspended chandelier!
        ix, iz = round(26 * math.cos(rad)), round(26 * math.sin(rad))
        c.add_curved_arch(tx, 24, tz, ix, 29, iz, 34, ["quartz_bricks", "light_blue_stained_glass"], 0)
        c.add_hanging_chandelier(ix, iz, 28, 5, "sea_lantern", "blue_ice", "end_rod[facing=down]")

    # 4 Clean Quartz Crystal Pillars at r=21 for Potion PvP kite loops
    for dx, dz in ((-15, -15), (15, -15), (-15, 15), (15, 15)):
        c.pillar(dx, dz, 2, 6, "quartz_pillar", 1)
        c.rel_put(dx, 7, dz, "blue_ice")
        c.rel_put(dx, 8, dz, "sea_lantern")

    c.add_spectator_pavilion(-28, 10, ["quartz_bricks", "prismarine_bricks"], "quartz_pillar", "prismarine_wall", "quartz_slab[type=bottom]", "sea_lantern")
    return c


# ---------------------------------------------------------------------------
# 9. Grand Event Spawn Lobby — Floating Sky Citadel Hub (96x46x96, y_origin=14)
# ---------------------------------------------------------------------------

def build_spawn_lobby() -> VoxelCanvas:
    # y_origin=14 gives 14 full blocks of sculpted floating island rock underside hanging into the void!
    c = VoxelCanvas(96, 46, 96, y_origin=14)
    c.add_floating_underside(31.0, 14, ["deepslate", "tuff", "stone", "andesite", "blackstone"], seed=91)

    # Grand Royal Floating Plaza at y=1
    c.disk(30, 0, "deepslate_bricks")
    c.disk(29, 1, ["quartz_bricks", "smooth_quartz", "polished_diorite"], seed=92)
    c.disk(20, 1, ["polished_blackstone_bricks", "polished_blackstone", "deepslate_tiles"], seed=93)
    c.disk(12, 1, ["quartz_bricks", "chiseled_quartz_block"])
    c.disk(6, 1, "gold_block")
    c.disk(3, 1, "sea_lantern")

    # Radiant Compass Rose Lanes
    for t in range(-28, 29):
        for w in (-1, 0, 1):
            blk = "gold_block" if (w == 0 and abs(t) % 3 == 0) else ("cyan_terracotta" if w == 0 else "polished_blackstone_bricks")
            c.rel_put(t, 1, w, blk)
            c.rel_put(w, 1, t, blk)

    # Soaring Golden & Quartz Crown Canopy Ribs arcing 26 blocks high above the Spawn Center!
    for dx, dz in ((-16, -16), (16, -16), (-16, 16), (16, 16)):
        c.pillar(dx, dz, 2, 14, "quartz_pillar", 1)
        c.rel_put(dx, 15, dz, "gold_block")
        c.add_curved_arch(dx, 14, dz, 0, 26, 0, 29, ["gold_block", "quartz_bricks"], 0)
    # Floating Glowing Crystal Beacon Diamond at apex (0, 23..28, 0)
    for oy in range(-3, 4):
        rad = 3 - abs(oy)
        for ox in range(-rad, rad + 1):
            for oz in range(-rad, rad + 1):
                if abs(ox) + abs(oz) <= rad:
                    c.rel_put(ox, 25 + oy, oz, "sea_lantern" if abs(oy) <= 1 and ox == 0 and oz == 0 else "cyan_stained_glass")

    # Ornamental Perimeter Balustrade + Lantern Columns
    for deg in range(0, 360, 4):
        rad = math.radians(deg)
        dx, dz = round(28.5 * math.cos(rad)), round(28.5 * math.sin(rad))
        c.rel_put(dx, 2, dz, "polished_blackstone_brick_wall")
        if deg % 30 == 0:
            c.pillar(dx, dz, 2, 6, "quartz_pillar", 0)
            c.rel_put(dx, 7, dz, "gold_block")
            c.rel_put(dx, 8, dz, "lantern")

    # 4 Iconic Themed Event Portal Shrines (N: Nether, S: End Crystal, E: Colosseum, W: Frost Temple)
    shrines = [
        (0, -23, "red_nether_bricks", "blackstone", "shroomlight", "red_stained_glass"),
        (0, 23, "obsidian", "purpur_block", "amethyst_block", "purple_stained_glass"),
        (23, 0, "smooth_sandstone", "quartz_pillar", "gold_block", "yellow_stained_glass"),
        (-23, 0, "prismarine_bricks", "quartz_pillar", "sea_lantern", "cyan_stained_glass"),
    ]
    for gx, gz, m1, m2, glow, glass in shrines:
        is_ns = (gx == 0)
        for side in (-4, 4):
            px, pz = (side, gz) if is_ns else (gx, side)
            c.pillar(px, pz, 2, 12, [m1, m2], 1, seed=96)
            c.rel_put(px, 13, pz, glow)
        for span in range(-4, 5):
            for y in range(2, 12):
                ax, az = (span, gz) if is_ns else (gx, span)
                if abs(span) <= 2 and y <= 10:
                    c.rel_put(ax, y, az, glass)
                elif y >= 11:
                    c.rel_put(ax, y, az, m1)
            ax, az = (span, gz) if is_ns else (gx, span)
            c.rel_put(ax, 12, az, glow if span == 0 else m2)
            if abs(span) <= 2:
                c.rel_put(ax, 13, az, m1)
        # Flank braziers
        for side in (-6, 6):
            bx, bz = (side, gz + (2 if gz < 0 else -2)) if is_ns else (gx + (2 if gx < 0 else -2), side)
            c.add_brazier(bx, 2, bz, m2, "campfire[lit=true]")

    # Floating Illuminated Spiral Sky-Parkour circling the Lobby (20 jumps up to a Golden Trophy Pad!)
    parkour_colors = ["lime_concrete", "cyan_concrete", "light_blue_concrete", "yellow_concrete", "orange_concrete", "magenta_concrete"]
    c.fill_box(20, 2, -20, 24, 2, -17, "gold_block")
    for step in range(20):
        ang = math.radians(-38 + step * 17)
        px = round(38 * math.cos(ang))
        pz = round(38 * math.sin(ang))
        py = 3 + int(step * 0.85)
        blk = parkour_colors[step % len(parkour_colors)]
        c.fill_box(px - 1, py, pz - 1, px + 1, py, pz + 1, blk)
        c.rel_put(px, py - 1, pz, "sea_lantern")
        if step == 19:
            c.fill_box(px - 2, py, pz - 2, px + 2, py, pz + 2, "gold_block")
            c.rel_put(px, py + 1, pz, "diamond_block")
            c.rel_put(px, py + 2, pz, "sea_lantern")

    c.clear_cylinder(0, 0, 2, 10, 5.5)
    return c


# ---------------------------------------------------------------------------
# 10. Royal Champions Winner Podium & Stadium Crowd (76x36x76, y_origin=10)
# ---------------------------------------------------------------------------

def build_winner_podium() -> VoxelCanvas:
    c = VoxelCanvas(76, 36, 76, y_origin=10)
    c.add_floating_underside(27.0, 10, ["deepslate", "tuff", "stone", "polished_blackstone"], seed=101)

    c.disk(26, 0, "deepslate_bricks")
    c.disk(25, 1, ["quartz_bricks", "smooth_quartz", "chiseled_quartz_block"], seed=102)
    c.disk(20, 1, ["polished_blackstone", "polished_blackstone_bricks"], seed=103)
    c.disk(16, 1, "quartz_bricks")

    # Royal Red Velvet Carpet leading from the Crowd Arena straight to the Champion's Gold Podium
    c.fill_box(-3, 1, -12, 3, 1, 18, "red_wool")
    for z in range(-12, 19):
        c.rel_put(-4, 1, z, "gold_block")
        c.rel_put(4, 1, z, "gold_block")

    # Sweeping Semi-Circular Marble Spectator Bleachers on South/East/West sides facing the Podium!
    for tier in range(5):
        r_in = 17 + tier * 1.4
        r_out = r_in + 1.5
        for deg in range(-20, 201, 2):
            rad = math.radians(deg)
            for rr in (r_in, r_out):
                dx, dz = round(rr * math.cos(rad)), round(rr * math.sin(rad))
                if abs(dx) <= 4 and dz > 0:
                    continue
                for y in range(2, 2 + tier + 1):
                    c.rel_put(dx, y, dz, "quartz_bricks" if y == 2 + tier else "smooth_quartz")

    # Perimeter Golden Columns & Hanging Lanterns
    for deg in range(0, 360, 5):
        rad = math.radians(deg)
        dx, dz = round(24.5 * math.cos(rad)), round(24.5 * math.sin(rad))
        c.rel_put(dx, 2, dz, "polished_blackstone_brick_wall")
        if deg % 30 == 0:
            c.pillar(dx, dz, 2, 12, "quartz_pillar", 0)
            c.rel_put(dx, 13, dz, "gold_block")
            c.rel_put(dx, 14, dz, "sea_lantern")

    # 1st Place GRAND GOLD CHAMPION PODIUM (0, y=2..4, z=-6) -> Champion stands at y=5
    c.fill_box(-3, 2, -9, 3, 4, -3, "gold_block")
    c.rel_put(0, 4, -6, "sea_lantern")
    c.fill_box(-2, 2, -2, 2, 2, -2, "quartz_stairs[facing=north,half=bottom]")
    # 2nd Place IRON Podium Left (-8, y=2..3, z=-6)
    c.fill_box(-10, 2, -8, -6, 3, -4, "iron_block")
    # 3rd Place COPPER Podium Right (+8, y=2..2, z=-6)
    c.fill_box(6, 2, -8, 10, 2, -4, "waxed_copper_block")

    # Monumental Golden Crown Triumph Arch behind the Champion (z=-14, height 23!)
    for sx in (-10, 10):
        c.pillar(sx, -14, 2, 18, "quartz_pillar", 1)
        c.rel_put(sx, 19, -14, "gold_block")
        c.rel_put(sx, 20, -14, "sea_lantern")
        c.add_brazier(sx, 2, -10, "gold_block", "campfire[lit=true]")
    c.add_curved_arch(-10, 16, -14, 10, 16, -14, 23, ["gold_block", "quartz_bricks"], 1)
    # Glowing Golden Crown Spire on Arch Center
    c.rel_put(0, 24, -14, "diamond_block")
    c.rel_put(0, 25, -14, "sea_lantern")

    c.clear_cylinder(0, -6, 5, 12, 2.5)
    c.clear_cylinder(0, 9, 2, 8, 4.5)
    return c


# ---------------------------------------------------------------------------
# Arena Metadata, Spawn Generation & Mathematical Clearance Verification
# ---------------------------------------------------------------------------

ARENA_SPECS = [
    ("nethpot", "NethPot - Gothic Nether Citadel", 0, 80, 0, build_nethpot, 30, 18, 10, -32, 12),
    ("axe-shield", "Axe & Shield - Ironkeep Castle", 300, 80, 0, build_axe_shield, 24, 15, 8, -29, 12),
    ("build-uhc", "Build UHC - Verdant Valley", 600, 80, 0, build_build_uhc, 22, 14, 8, -29, 14),
    ("sword", "Sword - Grand Roman Colosseum", 900, 80, 0, build_sword, 24, 15, 8, -28, 11),
    ("uhc", "UHC - Alpine Wild Frontier", 1200, 80, 0, build_uhc, 24, 15, 8, -31, 14),
    ("crystal", "Crystal - End Rift Spires", 1600, 80, 0, build_crystal, 26, 16, 8, -31, 12),
    ("smp", "SMP - Fallen Cathedral", 1900, 80, 0, build_smp, 26, 16, 8, -31, 13),
    ("diamond-pot", "Diamond Pot - Frostglass Cathedral", 2200, 80, 0, build_diamond_pot, 24, 15, 8, -28, 11),
]


def verify_and_clear_spawn(c: VoxelCanvas, dx: int, dy: int, dz: int, label: str) -> None:
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

        for group_name, group in (("wave1", w1), ("wave2", w2), ("final", fin), ("audience", aud)):
            for idx, sp in enumerate(group):
                if group_name != "audience":
                    canvas.clear_cylinder(sp["dx"], sp["dz"], sp["dy"], sp["dy"] + 3, 1.5)
                verify_and_clear_spawn(canvas, sp["dx"], sp["dy"], sp["dz"], f"{arena_id}.{group_name}[{idx}]")

        payload = canvas.encode()
        for out_dir in OUT_DIRS:
            with open(os.path.join(out_dir, f"{arena_id}.schem"), "wb") as f:
                f.write(payload)

        half_w = canvas.w // 2
        half_l = canvas.l // 2
        max_y = py + max(canvas.h + 10, 48 if arena_id == "build-uhc" else 44)
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
        print(f"OK {arena_id:13s} | {canvas.w:3d}x{canvas.h:2d}x{canvas.l:3d} | palette={len(canvas.palette):2d} | {len(payload):6d} bytes | all 15 spawns verified")

    write_arenas_yml(yml_entries)
    print(f"Updated {ARENAS_YML}")

    extras = [
        ("lobby", build_spawn_lobby, [(0, 2, 0, "lobby.spawn")]),
        ("winner-podium", build_winner_podium, [(0, 5, -6, "winner-podium.champion"), (0, 2, 9, "winner-podium.crowd")]),
    ]
    for extra_id, fn, checks in extras:
        c = fn()
        for dx, dy, dz, lbl in checks:
            verify_and_clear_spawn(c, dx, dy, dz, lbl)
        payload = c.encode()
        for out_dir in OUT_DIRS:
            with open(os.path.join(out_dir, f"{extra_id}.schem"), "wb") as f:
                f.write(payload)
        print(f"OK {extra_id:13s} | {c.w:3d}x{c.h:2d}x{c.l:3d} | palette={len(c.palette):2d} | {len(payload):6d} bytes | spawns verified")


if __name__ == "__main__":
    main()

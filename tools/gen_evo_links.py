"""Generates the connector sprites used by the evolution tree menu.

Each sprite is one half-column cell of a connector row. Lines sit on the cell
edges so two neighbouring cells together form one centred line. Bit flags:
  1 = TL (top vertical, left edge)     2 = TR (top vertical, right edge)
  4 = BL (bottom vertical, left edge)  8 = BR (bottom vertical, right edge)
 16 = H  (horizontal line through the middle, full width)
Output: src/main/resources/evolution/links/evo-link-<flags>.png (bundled in the plugin jar).
EvolutionSprites streams them to clients at runtime  ->  client region "net-evo-link-<flags>"
Sprites are white; the menu tints them per state.
"""
from pathlib import Path
from PIL import Image, ImageDraw

W, H = 80, 32      # 2x the in-menu cell size (40 x 16)
HALF_T = 4         # half line thickness per side (full line = 8px = 4 UI units)
MID = H // 2

out = Path(__file__).resolve().parent.parent / "src/main/resources/evolution/links"
out.mkdir(parents=True, exist_ok=True)

for flags in range(1, 32):
    img = Image.new("RGBA", (W, H), (255, 255, 255, 0))
    d = ImageDraw.Draw(img)
    white = (255, 255, 255, 255)
    # vertical halves overlap the horizontal band so corners are filled
    if flags & 1:  d.rectangle([0, 0, HALF_T - 1, MID + HALF_T - 1], fill=white)
    if flags & 2:  d.rectangle([W - HALF_T, 0, W - 1, MID + HALF_T - 1], fill=white)
    if flags & 4:  d.rectangle([0, MID - HALF_T, HALF_T - 1, H - 1], fill=white)
    if flags & 8:  d.rectangle([W - HALF_T, MID - HALF_T, W - 1, H - 1], fill=white)
    if flags & 16: d.rectangle([0, MID - HALF_T, W - 1, MID + HALF_T - 1], fill=white)
    img.save(out / f"evo-link-{flags}.png", optimize=True)

print(f"wrote 31 sprites to {out}")

# Cyclone logo: seven petals into the void

**The mark:**
- Seven petals split right through the rim, like the first Cyclone mark.
- The splits are logarithmic spirals, the curve of real cyclone arms and galaxies. Each one narrows in proportion to its radius, so the vortex looks the same at every scale and winds exponentially smaller into a black core: the center of a storm, or a wormhole.

| File | Use |
|---|---|
| `cyclone-wormhole-mark.svg` | Colour master: lit funnel gradient, void shadow and a soft top-left sheen. Dark and light backgrounds. |
| `cyclone-wormhole-mark-mono.svg` | One colour (`currentColor`). The splits stop at r = 14, so it stays clean at small sizes. |
| `cyclone-wormhole-app-icon.svg` | App icon tile. |
| `cyclone-wormhole-lockup-on-dark.svg` / `-on-light.svg` | Mark + "Cyclone" (Sora SemiBold, outlined). |
| `cyclone-wormhole-sheet.png` | Overview. |
| `src/` | Generator scripts. Run from `src/` with `python finalw.py` (needs shapely, fonttools, `Sora600.ttf`). |

## Construction (200 × 200 grid)

- **Disc:** radius 90.
- **Splits:** seven, 51.4° apart, mirrored to turn counter-clockwise.
  - The centreline is followed in ln r: r = 90·e^(−s).
  - The pitch angle eases from 35° at the rim (crisp notches) to 18° inside, with τ = 1.2.
  - The width is 0.08·r, so each split narrows toward the center.
  - Each ends at r = 6 in colour and r = 14 in one colour.
- **Petal fill:** a radial gradient (r = 91):
  `#000405` → `#021316` (10%) → `#0B5458` (30%) → `#2EC2B9` (58%) → `#5BE3D6` (78%, the lip) → `#2AA9A3` (rim).
- **Void:** a radial shadow under the petals (r = 52), dark at the core and fading to transparent teal. This lets the splits sink into darkness on any background.
- **Sheen:** a linear overlay from white 28% at the top-left to deep teal 30% at the bottom-right.

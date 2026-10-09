# icongen: unit icons for `config/assets` units

Generates the `-full`, `-ui` and `-shadow` sprites for the data-patch units in `config/assets`:

- **Data units** are drawn by **the game's own drawing code** (`UnitType.draw`), so the icon looks like the unit does in game. That covers bodies built from `RegionPart` spam, vanilla sprites swapped in by a separate patch, invisible weapons, hidden parts, mirrored, scaled and rotated parts, and layer order.
- **Vanilla units** (`--units dagger,…`) use Mindustry's own unit-icon generator verbatim, so they are bit-identical to the official atlas (`selftest`).

```
./gradlew -p tools/icongen run                       # regenerate all icons in place
./gradlew -p tools/icongen run --args="--units dp-aractid-unit,dp-orion-unit"
./gradlew -p tools/icongen run --args="--out /tmp/icons"     # preview elsewhere
./gradlew -p tools/icongen selftest                  # vanilla units: bit-exact check
```

On the first run, Gradle sparse-clones the raw sprites of the configured Mindustry tag (needs `git`, about 10 MB) and downloads that version's client jar (about 90 MB). The version is set in `gradle.properties` (`mindustryVersion`, `arcHash`). It must match the version your server runs.

## How data units are rendered

1. **Load the data the way a client does.** `config/assets` goes through the real `ContentParser`/`DataPatcher`, in the client's order: content → `init()` → `loadIcon()`/`load()` → patches.
2. **Use the client's atlas.** Regions resolve against a reconstruction of the atlas a v160.7 client has while playing on the server (`IngameAtlas`):
   - **Which sprites exist** comes from the client jar's packed atlas, plus every data image as `dp-<file>`. Nothing else exists: a joining client does not generate outlines for data content at runtime.
   - **The pixels** are the sprites as they are *before* antialiasing. That's the raw sprite, or what the vanilla generator writes into the atlas (outlined bodies, `-outline` sprites, tread frames). Those are captured by running the verbatim generator over all vanilla units.
   - **Every vanilla sprite is checked:** `antialias(reconstruction)` must equal the jar's atlas region pixel for pixel. Sprites from other generators (e.g. turret part outlines like `titan-outline`) can't be reconstructed. For those the atlas pixels are used and the sprite is listed in the log.
3. **Draw the unit.** `UnitType.draw(unit)` runs in the pose the game uses for a unit carried as payload: facing up, at rest, full health. In that mode the game itself skips the shadow, legs, lights, abilities and beams. For the icon, the soft shadow, engine glow, trails and weapon shadows are also left out.
4. **Rasterize in software.** `SoftBatch` is a software copy of arc's sorted `SpriteBatch`, at 1 sprite pixel = 1 icon pixel. It copies the vertex math, the stable z-sort key, the shader's colour/mix formula and the GL blend functions.
5. **Finish the icon.**
   - Cells use the vanilla icon palette, like every vanilla unit icon (in game they are tinted with the team colour).
   - The image is cropped symmetrically around the unit's centre, because the game draws `fullIcon`/`shadowRegion` centred on the unit.
   - `-ui` is the full icon scaled to fit at most 128×128 (vanilla). `-shadow` is a copy of `-full` (vanilla uses `shadowRegion = fullIcon`; only its alpha matters).
6. **Write un-antialiased.** The game antialiases data images once when it loads them (`DataImagePacker`), like vanilla's pack step does for vanilla sprites.

### Jank it handles, and reports

| Situation | What happens |
|---|---|
| **Body/weapons made of `RegionPart`s** with vanilla sprite names (`reign`, `scepter-weapon`, `mega` scaled 1.3×…) | Drawn exactly like the game: layers, mirroring, scale and rotation. |
| **Weapons without an in-game sprite** (data weapons are renamed `dp-<name>`) | Invisible, like in game. |
| **Vanilla regions swapped in by a patch** (`region: conquer`, `parts.8.regions: [scathe-mid]`) | Used, because the patches are applied before drawing. |
| **Parts hidden far away** (`y: 2000`) | Left out. Anything entirely outside the unit's `clipSize` square, which the game uses for culling, isn't part of the unit. |
| **Missing sprites** (e.g. `outline: true` on a sprite with no `-outline` region, or a mech without `baseRegion`) | The client draws its error sprite ("oh no") there, and so does the icon. If it's completely covered (the usual case), that's only logged. If it is visible, a warning is printed: the game shows it too. |
| **`drawBody: false`** units (body made of parts) | Work normally. |

### Verifying against the real client

`clientprobe/` is a small client mod. It loads `config/assets` through the client's own `DataManager` (like joining the server) and renders every data unit with the real GL renderer into PNGs:

```
./gradlew -p tools/icongen probeJar
cp tools/icongen/build/libs/icongen-probe.jar ~/.local/share/Mindustry/mods/
java -Dprobe.assets=$PWD/config/assets -Dprobe.out=/tmp/probe -jar tools/icongen/build/vanilla/v160.7/Mindustry.jar
#   (headless machine: xvfb-run -a -s "-screen 0 1280x720x24" java ...)  The client exits when done.
./gradlew -p tools/icongen run --args="--verify --out /tmp/verify"   # same renders from icongen, for comparison
```

`--verify` draws with the game's final, antialiased pixels and team-tinted cells, i.e. exactly what the client draws. On v160.7, for all 19 units in this repo, 99.985 % of visible pixels are within 8/255 of the real client, and 85 % are identical. The rest is ±1–2 rounding, plus a few dozen pixels on rotated or scaled part edges from the GPU's fixed-point filtering. Remove the probe from your mods folder afterwards: it quits the game on startup.

## Vanilla units: why the generator, and how it stays exact

Vanilla builds unit icons at **build time** (`tools/.../Generators.java`, task `unit-icons`) from the **raw** sprites.

- `UnitIconGenerator` contains the body of that generator **byte-for-byte**: `Generators.java` lines 556–792 of v160.7, between `BEGIN/END VERBATIM` markers.
- `GenAtlas` ports `ImagePacker`'s sprite cache: same `find`, `get`, `has` and `drawScaledFit`.
- **Self-test**: `selftest` regenerates every vanilla unit, applies the pack step's antialiasing and compares with the official atlas in the client jar. On v160.7, all 136 images (68 units × full + ui) are bit-identical.

That generator is not a renderer, though. It composes a body sprite, weapons and the cell onto a body-sized canvas, ignores `DrawPart`s, and draws nothing for `drawBody: false`. That's why data units are rendered instead. The old behaviour is still available as `--mode generator`, with `--apply-hints` and `--wrecks`.

## Options

```
--vanilla-sprites <dir>  Mindustry core/assets-raw/sprites (passed automatically by gradle)
--assets <dir>           data asset folder (default: ../../config/assets)
--client-jar <jar>       Mindustry client jar (passed automatically by gradle)
--mode render|generator  how data units are made (default: render)
--units a,b              only these units ("dp-" optional; vanilla names allowed)
--out <dir>              write into <dir> instead of next to the existing sprites
                         (vanilla units without --out go to build/vanilla-icons)
--verify                 with --out: game-exact renders, to compare with the client probe
--apply-hints            generator mode: as if the suggested weapons.N.region lines were applied
--wrecks                 generator mode: also write <unit>-wreck0..2
--selftest <jar>         compare regenerated vanilla units with a client jar's atlas
```

Output names follow what the patches reference. For example, `fullIcon: dp-devastator-full` is written to `devastator-full.png`, and existing files are overwritten in place. Units without such references get `<unit>-full/-ui/-shadow.png` in `sprites/units/`, plus a suggested patch line. Set `ICONGEN_DEBUG=1` to log every draw call.

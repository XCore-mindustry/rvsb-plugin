package rvsb.icongen;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.TextureAtlas.*;
import arc.icongen.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;

/**
 * Port of the sprite-cache / atlas half of {@code mindustry.tools.ImagePacker} (Mindustry v160.7).
 *
 * Every method that the vanilla "unit-icons" generator body calls ({@code get}, {@code has}, {@code save},
 * {@code replace}, {@code drawCenter}, {@code drawScaledFit}, {@code validate}, {@code err}) has the same name,
 * signature and semantics as in ImagePacker, so that {@link UnitIconGenerator} can contain the vanilla code verbatim.
 *
 * Differences to ImagePacker, none of which change generated pixels:
 * <ul>
 *     <li>{@code save()} does not write to disk; it hands the pixmap to {@link #saveSink} so the caller can pick
 *     the files it wants (full icon, ui icon, wrecks). Auxiliary sprites (outlines, tread frames) are dropped.</li>
 *     <li>{@code replace()} doesn't touch the cache. In ImagePacker it writes an outlined copy to the output folder and
 *     deletes the raw input file, but the in-memory pixmap cache - which is what every later {@code get()} reads - is
 *     never updated, so it has no effect on generated icons. (It does define what the packed game atlas contains;
 *     {@link IngameAtlas} captures it through {@link #replaceSink}.)</li>
 *     <li>Images can be registered with a name prefix ("dp-" for data-patch images, exactly like DataImagePacker),
 *     and "virtual" entries can be registered whose pixmap is produced on demand (used to emulate generated vanilla
 *     atlas regions such as {@code unit-corvus-full} when a data patch references them).</li>
 * </ul>
 */
public class GenAtlas{
    static ObjectMap<String, PackIndex> cache = new ObjectMap<>();

    /** Temporary name overrides: name -> other region name, or null to hide a region. See {@link #override}. */
    static ObjectMap<String, String> overrides = new ObjectMap<>();

    /** Receives everything the vanilla generator code would write to disk. */
    static @Nullable Cons2<Pixmap, String> saveSink;
    /** Receives every sprite the vanilla generator overwrites with {@code replace()} (name, new pixels). */
    static @Nullable Cons2<String, Pixmap> replaceSink;

    /**
     * When set, the atlas behaves like the atlas of a client playing on the server: names exist iff the game has
     * them, regions carry the in-game (pre-antialias) pixels, and looking up a missing name yields a region that
     * draws like the client's error region ("oh no" sprite) while reporting found() == false.
     */
    static @Nullable IngameAtlas ingame;
    static ObjectMap<String, GenRegion> ingameRegions = new ObjectMap<>();
    /** Missing regions that were actually drawn (they show up as the "oh no" error sprite in game). */
    static OrderedSet<String> drawnErrors = new OrderedSet<>();

    static GenRegion ingameRegion(String name){
        GenRegion region = ingameRegions.get(name);
        if(region == null){
            region = new GenRegion(name, null);
            Pixmap pix = ingame.pixmap(name);
            if(pix == null){
                region.invalid = true;
                pix = ingame.pixmap("error");
            }
            region.pixmap = pix;
            region.width = pix.width;
            region.height = pix.height;
            region.u2 = region.v2 = 1f;
            region.u = region.v = 0f;
            region.texture = SoftBatch.textureFor(region);
            ingameRegions.put(name, region);
        }
        return region;
    }

    /** A copy of a region whose pixels use the vanilla icon cell palette (see Generators "unit-icons"). */
    static GenRegion cellVariant(GenRegion region){
        GenRegion cell = new GenRegion(region.name, null);
        cell.invalid = region.invalid;
        cell.pixmap = region.pixmap.copy();
        if(!region.invalid){
            cell.pixmap.replace(in -> in == 0xffffffff ? 0xffa664ff : in == 0xdcc6c6ff || in == 0xdcc5c5ff ? 0xd06b53ff : 0);
        }
        cell.cell = true;
        cell.width = region.width;
        cell.height = region.height;
        cell.u2 = cell.v2 = 1f;
        cell.texture = SoftBatch.textureFor(cell);
        return cell;
    }

    /** Indexes every png below {@code root}, like ImagePacker does with assets-raw/sprites_out. */
    public static int index(Fi root, String prefix){
        int[] count = {0};
        root.walk(path -> {
            if(!path.extEquals("png")) return;

            cache.put(prefix + path.nameWithoutExtension(), new PackIndex(path));
            count[0]++;
        });
        return count[0];
    }

    /** Registers a region whose pixmap is created lazily. */
    public static void virtual(String name, Prov<Pixmap> generator){
        PackIndex index = new PackIndex(null);
        index.generator = generator;
        cache.put(name, index);
    }

    public static boolean contains(String name){
        return cache.containsKey(name);
    }

    /** Makes {@code name} resolve to region {@code target} (or to nothing if target is null) until {@link #clearOverrides()}. */
    public static void override(String name, @Nullable String target){
        overrides.put(name, target);
    }

    public static void clearOverrides(){
        overrides.clear();
    }

    static boolean exists(String name){
        if(ingame != null) return ingame.exists(name);
        if(overrides.containsKey(name)){
            String target = overrides.get(name);
            return target != null && cache.containsKey(target);
        }
        return cache.containsKey(name);
    }

    public static @Nullable Fi file(String name){
        PackIndex index = cache.get(name);
        return index == null ? null : index.file;
    }

    public static TextureAtlas create(){
        return new TextureAtlas(){
            @Override
            public AtlasRegion find(String name){
                if(ingame != null) return ingameRegion(name);
                if(overrides.containsKey(name)){
                    String target = overrides.get(name);
                    if(target != null) return find(target);
                    GenRegion region = new GenRegion(name, null);
                    region.invalid = true;
                    return region;
                }
                if(!cache.containsKey(name)){
                    GenRegion region = new GenRegion(name, null);
                    region.invalid = true;
                    return region;
                }

                PackIndex index = cache.get(name);
                if(index.pixmap == null){
                    index.pixmap = index.generator != null ? index.generator.get() : new Pixmap(index.file);
                    //(not an anonymous subclass like in ImagePacker: the class must stay in the arc.* package, see GenRegion)
                    GenRegion region = new GenRegion(name, index.file);
                    region.width = index.pixmap.width;
                    region.height = index.pixmap.height;
                    region.u2 = region.v2 = 1f;
                    region.u = region.v = 0f;
                    index.region = region;
                }
                return index.region;
            }

            @Override
            public AtlasRegion find(String name, TextureRegion def){
                if(!exists(name)){
                    return (AtlasRegion)def;
                }
                return find(name);
            }

            @Override
            public AtlasRegion find(String name, String def){
                if(!exists(name)){
                    return find(def);
                }
                return find(name);
            }

            @Override
            public PixmapRegion getPixmap(AtlasRegion region){
                if(ingame != null) return new PixmapRegion(((GenRegion)region).pixmap);
                return new PixmapRegion(get(region.name));
            }

            @Override
            public boolean isFound(TextureRegion region){
                return region instanceof GenRegion g ? !g.invalid : super.isFound(region);
            }

            @Override
            public boolean has(String s){
                return exists(s);
            }
        };
    }

    // ---- ImagePacker helpers, same semantics ----

    static Pixmap get(String name){
        return get(Core.atlas.find(name));
    }

    static boolean has(String name){
        return Core.atlas.has(name);
    }

    static Pixmap get(TextureRegion region){
        validate(region);

        return cache.get(((AtlasRegion)region).name).pixmap.copy();
    }

    static void save(Pixmap pix, String path){
        if(saveSink != null) saveSink.get(pix, path);
    }

    static void drawCenter(Pixmap pix, Pixmap other){
        pix.draw(other, pix.width/2 - other.width/2, pix.height/2 - other.height/2, true);
    }

    static void drawScaledFit(Pixmap base, Pixmap image){
        Vec2 size = Scaling.fit.apply(image.width, image.height, base.width, base.height);
        int wx = (int)size.x, wy = (int)size.y;
        //TODO bad linear scaling
        base.draw(image, 0, 0, image.width, image.height, base.width/2 - wx/2, base.height/2 - wy/2, wx, wy, true, true);
    }

    static void replace(String name, Pixmap image){
        replace(name, name, image);
    }

    static void replace(String path, String name, Pixmap image){
        //no effect on later get() calls, see class javadoc; only reported to the in-game atlas capture
        if(replaceSink != null) replaceSink.get(name, image);
    }

    static void replace(TextureRegion region, Pixmap image){
        replace(((GenRegion)region).name, image);
    }

    static void err(String message, Object... args){
        throw new IllegalArgumentException(Strings.format(message, args));
    }

    static void validate(TextureRegion region){
        if(((GenRegion)region).invalid){
            err("Region does not exist: @", ((GenRegion)region).name);
        }
    }

    static class PackIndex{
        @Nullable AtlasRegion region;
        @Nullable Pixmap pixmap;
        @Nullable Prov<Pixmap> generator;
        Fi file;

        public PackIndex(Fi file){
            this.file = file;
        }
    }
}

package rvsb.icongen;

import arc.graphics.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.mod.*;
import mindustry.type.*;

/**
 * The sprites a v160.7 client has in its atlas while it plays on this server, in their <b>pre-antialias</b> form.
 *
 * <ul>
 *     <li>Which names exist: exactly the regions of the client jar's packed atlas, plus every data-patch image as
 *     "dp-&lt;file name&gt;" (DataImagePacker). Nothing else: a joining client does not run createIcons() for data
 *     content (DataManager.regenerateContentSprites is only called by the map editor), so no "-outline" sprites
 *     etc. are generated for it at runtime.</li>
 *     <li>Pixels of vanilla sprites: what Anuke's ImagePacker feeds into the pack step. That is the raw sprite,
 *     unless the vanilla "unit-icons" generator overwrote it with {@code replace()} (outlined unit bodies / legs /
 *     top weapons) or created it with {@code save()} ("-outline" sprites, tread animation frames, "unit-x-full"...).
 *     Those outputs are captured by running the verbatim generator over all vanilla units in content order.</li>
 *     <li>Every vanilla sprite is verified: antialiasing it (the pack step) must reproduce the jar's atlas region
 *     pixel for pixel. If it doesn't (a sprite made by some other generator, e.g. block outlines), the jar's
 *     pixels are used instead and the sprite is reported - it is then antialiased twice in the final icon.</li>
 *     <li>Data-patch images: the raw files (the game antialiases them when it packs them, like our output).</li>
 * </ul>
 */
public class IngameAtlas{
    public final JarAtlas jar;
    final ObjectMap<String, Pixmap> generated = new ObjectMap<>();
    final ObjectMap<String, Pixmap> resolved = new ObjectMap<>();
    final ObjectSet<String> missing = new ObjectSet<>();
    /** name -> why the (already antialiased) atlas pixels had to be used */
    public final OrderedMap<String, String> fromJar = new OrderedMap<>();

    /**
     * Verification only: use the game's final (antialiased) pixels for everything - the jar atlas for vanilla sprites,
     * antialiased data images - so a render can be compared pixel by pixel with a real client's render.
     */
    public boolean postAA;

    public IngameAtlas(JarAtlas jar){
        this.jar = jar;
    }

    /** Runs the verbatim vanilla generator over every vanilla unit and records what it writes into the atlas input. */
    public void captureVanilla(){
        int count = 0;
        for(UnitType type : Vars.content.units()){
            if(type.minfo.mod != null) continue;
            UnitIconGenerator.capture(type, (name, pix) -> {
                String key = name.substring(name.lastIndexOf('/') + 1);
                Pixmap old = generated.put(key, pix.copy());
                if(old != null) old.dispose();
            });
            count++;
        }
        Log.info("Captured @ generated vanilla sprites (outlined bodies, -outline sprites, tread frames, icons) from @ units",
            generated.size, count);
    }

    public boolean exists(String name){
        if(name.startsWith(DataImagePacker.regionPrefix)) return GenAtlas.cache.containsKey(name);
        return jar.has(name);
    }

    /** @return the in-game sprite before antialiasing, or null if the game has no such sprite. Do not dispose. */
    public @Nullable Pixmap pixmap(String name){
        Pixmap pix = resolved.get(name);
        if(pix != null || missing.contains(name)) return pix;

        pix = resolve(name);
        if(pix == null) missing.add(name);
        else resolved.put(name, pix);
        return pix;
    }

    @Nullable Pixmap resolve(String name){
        if(name.startsWith(DataImagePacker.regionPrefix)){
            GenAtlas.PackIndex index = GenAtlas.cache.get(name);
            if(index == null) return null;
            Pixmap pix = index.generator != null ? index.generator.get() : new Pixmap(index.file);
            if(postAA) Pixmaps.antialias(pix);
            return pix;
        }
        if(!jar.has(name)) return null;
        if(postAA) return jar.crop(name);

        Pixmap atlas = jar.crop(name);
        Pixmap candidate = generated.get(name);
        String kind = "generated";
        if(candidate == null){
            GenAtlas.PackIndex index = GenAtlas.cache.get(name);
            if(index != null && index.file != null){
                candidate = new Pixmap(index.file);
                kind = "raw";
            }
        }else{
            candidate = candidate.copy();
        }

        if(candidate == null){
            fromJar.put(name, "only exists in the packed atlas (made by a non-unit generator)");
            return atlas;
        }

        Pixmap aa = candidate.copy();
        Pixmaps.antialias(aa);
        String diff = IconGen.compare(aa, atlas);
        aa.dispose();
        if(diff != null){
            fromJar.put(name, kind + " sprite doesn't match the atlas after antialiasing (" + diff + ")");
            candidate.dispose();
            return atlas;
        }
        atlas.dispose();
        return candidate;
    }
}

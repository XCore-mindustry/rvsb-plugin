package rvsb.icongen;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import arc.util.noise.*;
import mindustry.entities.part.*;
import mindustry.gen.*;
import mindustry.type.*;
import arc.icongen.*;

import static mindustry.Vars.*;
import static rvsb.icongen.GenAtlas.*;

/**
 * The vanilla unit icon generator.
 *
 * Everything between the BEGIN/END VERBATIM markers is copied byte-for-byte from
 * {@code tools/src/mindustry/tools/Generators.java}, lines 556-792, of Mindustry tag v160.7
 * (commit 17012d61d9e4c3cbd9820d87e81c24495486ac53) - the body of the {@code "unit-icons"} generator lambda.
 * It runs against {@link GenAtlas}, a port of ImagePacker's sprite cache, so it sees exactly what it sees when
 * Anuke packs the game's sprites: raw, un-outlined, un-antialiased images.
 *
 * Like in vanilla, the returned full/ui pixmaps are the images *before* antialiasing. Vanilla's gradle "pack" task
 * antialiases them afterwards; for data-patch images the game does the same thing itself when it loads them
 * ({@code DataImagePacker.pack -> Pixmaps.antialias}), so the files must be written un-antialiased.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class UnitIconGenerator{
    static final int maxUiIcon = 128;

    public static class Result{
        public @Nullable Pixmap full, ui;
        public Pixmap[] wrecks = new Pixmap[3];
    }

    public static Result generate(UnitType type){
        Result result = new Result();
        String wreckPrefix = "../rubble/" + type.name + "-wreck";
        //the vanilla code mutates the weapon list (removes weapons without sprites); undo that afterwards
        Seq<Weapon> weaponsBefore = type.weapons.copy();
        //the tread rects are only used to slice tread *animation frames*, which are not part of any icon (and are
        //discarded here). Data patches often reuse vanilla tread frames with rects that don't fit the tread sprite,
        //which would crash that part, so skip it. The treads drawn into the icon don't depend on the rects.
        Rect[] treadRectsBefore = type.treadRects;
        type.treadRects = new Rect[0];
        //DrawParts never end up in the icon pixels: the generator only uses them to write "-outline" sprites and to
        //decide how weapon outlines are saved (both discarded here, and replace() never feeds back into the cache).
        //But a part whose sprite doesn't exist makes get() throw, which aborts the whole icon. Hide them while generating.
        Seq<DrawPart> partsBefore = type.parts;
        type.parts = new Seq<>();
        ObjectMap<Weapon, Seq<DrawPart>> weaponPartsBefore = new ObjectMap<>();
        for(Weapon weapon : type.weapons){
            weaponPartsBefore.put(weapon, weapon.parts);
            weapon.parts = new Seq<>();
        }

        saveSink = (pix, path) -> {
            if(path.equals("unit-" + type.name + "-full")){
                result.full = pix.copy();
            }else if(path.equals("../ui/unit-" + type.name + "-ui")){
                result.ui = pix.copy();
            }else if(path.startsWith(wreckPrefix)){
                result.wrecks[Integer.parseInt(path.substring(wreckPrefix.length()))] = pix.copy();
            }
            //everything else (outline sprites, tread frames, segment sprites) is not needed
        };

        try{
            run(type);
        }finally{
            saveSink = null;
            type.weapons.set(weaponsBefore);
            type.treadRects = treadRectsBefore;
            type.parts = partsBefore;
            weaponPartsBefore.each((weapon, parts) -> weapon.parts = parts);
        }
        return result;
    }

    /**
     * Runs the generator exactly like ImagePacker does (nothing hidden; only meant for vanilla units) and reports
     * every sprite it writes - {@code replace()}d atlas inputs and {@code save()}d files - as (name or path, pixels).
     * Used to reconstruct the pre-antialias in-game atlas, see {@link IngameAtlas}.
     */
    public static void capture(UnitType type, Cons2<String, Pixmap> sink){
        Seq<Weapon> weaponsBefore = type.weapons.copy();
        saveSink = (pix, path) -> sink.get(path, pix);
        replaceSink = sink;
        try{
            run(type);
        }finally{
            saveSink = null;
            replaceSink = null;
            type.weapons.set(weaponsBefore);
        }
    }

    static void run(UnitType type){
        //BEGIN VERBATIM (Generators.java v160.7, lines 556-792)
            if(type.internal && !type.internalGenerateSprites) return; //internal hidden units don't generate

            ObjectSet<String> outlined = new ObjectSet<>();

            try{
                Unit sample = type.constructor.get();

                Func<Pixmap, Pixmap> outline = i -> i.outline(type.outlineColor, 3);
                Cons<TextureRegion> outliner = t -> {
                    if(t != null && t.found()){
                        replace(t, outline.get(get(t)));
                    }
                };

                Seq<TextureRegion> toOutline = new Seq<>();
                type.getRegionsToOutline(toOutline);

                for(TextureRegion region : toOutline){
                    Pixmap pix = get(region).outline(type.outlineColor, type.outlineRadius);
                    save(pix, ((GenRegion)region).name + "-outline");
                }

                Seq<DrawPart> allParts = new Seq<>();

                //this code is complete trash
                Cons<Seq<DrawPart>>[] allDrawIter = new Cons[]{null};
                allDrawIter[0] = seq -> {
                    for(DrawPart part : seq){
                        allParts.add(part);
                        if(part instanceof RegionPart){
                            allDrawIter[0].get(((RegionPart)part).children);
                        }
                    }
                };
                allDrawIter[0].get(type.parts);

                for(DrawPart part : allParts){
                    if(part instanceof RegionPart && ((RegionPart)part).replaceOutline){
                        for(TextureRegion r : ((RegionPart)part).regions){
                            outliner.get(r);
                        }
                    }
                }

                Seq<Weapon> weapons = type.weapons;
                weapons.each(Weapon::load);
                weapons.removeAll(w -> !w.region.found());

                for(Weapon weapon : weapons){
                    if(outlined.add(weapon.name) && has(weapon.name)){
                        //only non-top weapons need separate outline sprites (this is mostly just mechs)
                        if(!weapon.top || weapon.parts.contains(p -> p.under)){
                            save(outline.get(get(weapon.name)), weapon.name + "-outline");
                        }else{
                            //replace weapon with outlined version, no use keeping standard around
                            outliner.get(weapon.region);
                        }
                    }
                }

                //generate tank animation
                if(sample instanceof Tankc){
                    Pixmap pix = get(type.treadRegion);

                    for(int r = 0; r < type.treadRects.length; r++){
                        Rect treadRect = type.treadRects[r];
                        //slice is always 1 pixel wide
                        Pixmap slice = pix.crop((int)(treadRect.x + pix.width/2f), (int)(treadRect.y + pix.height/2f), 1, (int)treadRect.height);
                        int frames = type.treadFrames;
                        for(int i = 0; i < frames; i++){
                            int pullOffset = type.treadPullOffset;
                            Pixmap frame = new Pixmap(slice.width, slice.height);
                            for(int y = 0; y < slice.height; y++){
                                int idx = y + i;
                                if(idx >= slice.height){
                                    idx -= slice.height;
                                    idx += pullOffset;
                                    idx = Mathf.mod(idx, slice.height);
                                }

                                frame.setRaw(0, y, slice.getRaw(0, idx));
                            }
                            save(frame, type.name + "-treads" + r + "-" + i);
                        }
                    }
                }

                outliner.get(type.jointRegion);
                outliner.get(type.footRegion);
                outliner.get(type.legBaseRegion);
                outliner.get(type.baseJointRegion);
                if(sample instanceof Legsc) outliner.get(type.legRegion);
                if(sample instanceof Tankc) outliner.get(type.treadRegion);

                //TODO: for drawBody false, an empty pixmap is used; this is a hack
                Pixmap image = type.segments > 0 ? get(type.segmentRegions[0]) : type.drawBody ? outline.get(get(type.previewRegion)) : new Pixmap(1, 1);

                Func<Weapon, Pixmap> weaponRegion = weapon -> Core.atlas.has(weapon.name + "-preview") ? get(weapon.name + "-preview") : get(weapon.region);
                Cons2<Weapon, Pixmap> drawWeapon = (weapon, pixmap) ->
                image.draw(weapon.flipSprite ? pixmap.flipX() : pixmap,
                (int)(weapon.x / Draw.scl + image.width / 2f - weapon.region.width / 2f),
                (int)(-weapon.y / Draw.scl + image.height / 2f - weapon.region.height / 2f),
                true
                );

                boolean anyUnder = false;

                //draw each extra segment on top before it is saved as outline
                if(sample instanceof Crawlc){
                    for(int i = 0; i < type.segments; i++){
                        //replace(type.segmentRegions[i], outline.get(get(type.segmentRegions[i])));
                        save(outline.get(get(type.segmentRegions[i])), type.name + "-segment-outline" + i);

                        if(i > 0){
                            drawCenter(image, get(type.segmentRegions[i]));
                        }
                    }
                    save(image, type.name);
                }

                //outline is currently never needed, although it could theoretically be necessary
                if(type.needsBodyOutline()){
                    save(image, type.name + "-outline");
                }else if(type.segments == 0 && type.drawBody){
                    replace(type.name, type.segments > 0 ? get(type.segmentRegions[0]) : outline.get(get(type.region)));
                }

                //draw weapons that are under the base
                for(Weapon weapon : weapons.select(w -> w.layerOffset < 0)){
                    drawWeapon.get(weapon, outline.get(weaponRegion.get(weapon)));
                    anyUnder = true;
                }

                //draw over the weapons under the image
                if(anyUnder){
                    image.draw(outline.get(get(type.previewRegion)), true);
                }

                //draw treads
                if(sample instanceof Tankc){
                    Pixmap treads = outline.get(get(type.treadRegion));
                    image.draw(treads, image.width / 2 - treads.width / 2, image.height / 2 - treads.height / 2, true);
                    image.draw(get(type.previewRegion), true);
                }

                //draw mech parts
                if(sample instanceof Mechc){
                    drawCenter(image, get(type.baseRegion));
                    drawCenter(image, get(type.legRegion));
                    drawCenter(image, get(type.legRegion).flipX());
                    image.draw(get(type.previewRegion), true);
                }

                //draw weapon outlines on base
                for(Weapon weapon : weapons){
                    //skip weapons under unit
                    if(weapon.layerOffset < 0) continue;

                    drawWeapon.get(weapon, outline.get(weaponRegion.get(weapon)));
                }

                //draw base region on top to mask weapons
                if(type.drawCell) image.draw(get(type.previewRegion), true);

                if(type.drawCell){
                    Pixmap baseCell = get(type.cellRegion);
                    Pixmap cell = baseCell.copy();

                    //replace with 0xffd37fff : 0xdca463ff for sharded colors?
                    cell.replace(in -> in == 0xffffffff ? 0xffa664ff : in == 0xdcc6c6ff || in == 0xdcc5c5ff ? 0xd06b53ff : 0);

                    image.draw(cell, image.width / 2 - cell.width / 2, image.height / 2 - cell.height / 2, true);
                }

                for(Weapon weapon : weapons){
                    //skip weapons under unit
                    if(weapon.layerOffset < 0) continue;

                    Pixmap reg = weaponRegion.get(weapon);
                    Pixmap wepReg = weapon.top ? outline.get(reg) : reg;

                    drawWeapon.get(weapon, wepReg);

                    if(weapon.cellRegion.found()){
                        Pixmap weaponCell = get(weapon.cellRegion);
                        weaponCell.replace(in -> in == 0xffffffff ? 0xffa664ff : in == 0xdcc6c6ff || in == 0xdcc5c5ff ? 0xd06b53ff : 0);
                        drawWeapon.get(weapon, weaponCell);
                    }
                }

                //TODO I can save a LOT of space by not creating a full icon.
                if(type.generateFullIcon){
                    save(image, "unit-" + type.name + "-full");
                }

                Rand rand = new Rand();
                rand.setSeed(type.name.hashCode());

                //generate random wrecks

                int splits = 3;
                float degrees = rand.random(360f);
                float offsetRange = Math.max(image.width, image.height) * 0.15f;
                Vec2 offset = new Vec2(1, 1).rotate(rand.random(360f)).setLength(rand.random(0, offsetRange)).add(image.width/2f, image.height/2f);

                Pixmap[] wrecks = new Pixmap[splits];
                for(int i = 0; i < wrecks.length; i++){
                    wrecks[i] = new Pixmap(image.width, image.height);
                }

                VoronoiNoise vn = new VoronoiNoise(type.id, true);

                image.each((x, y) -> {
                    //add darker cracks on top
                    boolean rValue = Math.max(Ridged.noise2d(1, x, y, 3, 1f / (20f + image.width/8f)), 0) > 0.16f;
                    //cut out random chunks with voronoi
                    boolean vval = vn.noise(x, y, 1f / (14f + image.width/40f)) > 0.47;

                    float dst =  offset.dst(x, y);
                    //distort edges with random noise
                    float noise = (float)Noise.rawNoise(dst / (9f + image.width/70f)) * (60 + image.width/30f);
                    int section = (int)Mathf.clamp(Mathf.mod(offset.angleTo(x, y) + noise + degrees, 360f) / 360f * splits, 0, splits - 1);
                    if(!vval) wrecks[section].setRaw(x, y, Color.muli(image.getRaw(x, y), rValue ? 0.7f : 1f));
                });

                for(int i = 0; i < wrecks.length; i++){
                    save(wrecks[i], "../rubble/" + type.name + "-wreck" + i);
                }

                int maxd = Math.min(Math.max(image.width, image.height), maxUiIcon);
                Pixmap fit = new Pixmap(maxd, maxd);
                drawScaledFit(fit, image);

                save(fit, "../ui/unit-" + type.name + "-ui");
            }catch(IllegalArgumentException e){
                Log.err("WARNING: Skipping unit @: @", type.name, e.getMessage());
            }
        //END VERBATIM
    }
}

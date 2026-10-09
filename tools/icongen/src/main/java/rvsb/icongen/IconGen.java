package rvsb.icongen;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.TextureAtlas.*;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData.*;
import arc.mock.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Log.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.ctype.*;
import mindustry.graphics.*;
import mindustry.logic.*;
import mindustry.mod.*;
import mindustry.mod.data.*;
import mindustry.type.*;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Generates uiIcon / fullIcon / shadow sprites for data-patch (config/assets) units.
 *
 * <ul>
 *     <li>Data units ({@code --mode render}, default): drawn by the game's own {@code UnitType.draw} code into a
 *     software sprite batch ({@link SoftBatch}), against a reconstruction of the client's atlas before antialiasing
 *     ({@link IngameAtlas}). The icon is what the unit looks like in game - RegionPart spam, vanilla sprites swapped in
 *     by patches, invisible weapons and all.</li>
 *     <li>Vanilla units (and data units with {@code --mode generator}): the verbatim vanilla generator
 *     ({@link UnitIconGenerator}), bit-exact with the official atlas (see --selftest).</li>
 * </ul>
 *
 * <pre>
 * Usage: IconGen --vanilla-sprites &lt;Mindustry/core/assets-raw/sprites&gt; [options]
 *   --assets &lt;dir&gt;         server data asset folder (config/assets), loaded exactly like a v160.7 client loads it
 *   --client-jar &lt;jar&gt;     Mindustry client jar of the same version (needed by --mode render)
 *   --mode render|generator how data units are made (default render)
 *   --units a,b,c          only generate these units (content names, "dp-" prefix optional; vanilla names allowed)
 *   --out &lt;dir&gt;            write everything into this folder instead of next to the existing sprites
 *   --apply-hints          (generator mode) generate as if the printed weapons.N.region suggestions were applied
 *   --wrecks               (generator mode) also write &lt;unit&gt;-wreck0..2
 *   --verify               (render mode, with --out) write game-exact renders to compare with the client probe
 *   --selftest &lt;jar&gt;       regenerate vanilla units and compare them pixel-by-pixel with the client jar's atlas
 * </pre>
 */
public class IconGen{
    static final Pattern generatedIconRef = Pattern.compile("unit-([a-z0-9-]+?)-(full|ui)(?![a-z0-9-])");

    static Fi vanillaSprites, assets, out, selftestJar, clientJar;
    static @Nullable ObjectSet<String> onlyUnits;
    static boolean wrecks, applyHints;
    /** render: draw data units with the game's own code (in-game look). generator: vanilla generator (old mode). */
    static String mode = "render";
    /** Write game-exact renders (antialiased atlas, team-tinted cells) for comparison with clientprobe output. */
    static boolean verify;

    static int warnings = 0;

    public static void main(String[] args) throws Exception{
        parseArgs(args);

        // ---- environment, identical to ImagePacker.main ----
        Vars.headless = true;
        ArcNativesLoader.load();

        Core.settings = new MockSettings();
        Log.logger = new NoopLogHandler();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        Log.logger = new DefaultLogHandler();

        int vanillaCount = GenAtlas.index(vanillaSprites, "");
        if(!GenAtlas.contains("scale_marker")){
            fail("'" + vanillaSprites + "' does not look like Mindustry's core/assets-raw/sprites folder (no scale_marker.png).");
        }
        Log.info("Indexed @ vanilla raw sprites from @", vanillaCount, vanillaSprites.absolutePath());

        if(assets != null && assets.child("sprites").exists()){
            //data-patch images are registered with the "dp-" prefix, exactly like DataImagePacker does
            int count = GenAtlas.index(assets.child("sprites"), "dp-");
            Log.info("Indexed @ data-patch sprites from @", count, assets.child("sprites").absolutePath());
        }

        Core.atlas = GenAtlas.create();
        Draw.scl = 1f / Core.atlas.find("scale_marker").width;

        Vars.content.load();

        if(selftestJar != null){
            selftest();
            return;
        }

        int written = 0;
        Seq<String> patchHints = new Seq<>();

        //vanilla units can be requested explicitly (previews / comparisons). They always use the verbatim generator,
        //and must be generated before the atlas is switched to in-game mode (the generator needs the raw sprite cache).
        if(onlyUnits != null){
            for(String name : onlyUnits){
                UnitType type = Vars.content.unit(name);
                if(type == null || type.minfo.mod != null) continue;
                UnitIconGenerator.Result result = UnitIconGenerator.generate(type);
                if(result.ui == null){
                    warn("@: the vanilla generator produced no icon (see the error above); skipped.", type.name);
                    continue;
                }
                written += write(result.full, "unit-" + type.name + "-full", null);
                written += write(result.ui, "unit-" + type.name + "-ui", null);
                if(wrecks) for(int i = 0; i < 3; i++) written += write(result.wrecks[i], type.name + "-wreck" + i, null);
                Log.info("@: full @x@, ui @x@ (vanilla generator)", type.name, result.full.width, result.full.height, result.ui.width, result.ui.height);
            }
        }

        Seq<UnitType> targets = new Seq<>();
        ObjectMap<UnitType, ContentAsset> unitAssets = new ObjectMap<>();
        boolean render = mode.equals("render");
        IngameAtlas ingame = null;

        if(assets != null){
            if(render){
                if(clientJar == null) fail("--mode render needs --client-jar <Mindustry.jar> (the v160.7 client; its atlas defines the in-game sprites).");
                Log.info("Reading the client atlas from @", clientJar.absolutePath());
                ingame = new IngameAtlas(new JarAtlas(clientJar));
                ingame.captureVanilla();
                ingame.postAA = verify;
                UnitRenderer.verify = verify;
                GenAtlas.ingame = ingame;
            }
            loadDataAssets(targets, unitAssets);
        }

        if(onlyUnits != null){
            targets.retainAll(t -> onlyUnits.contains(t.name) || onlyUnits.contains(stripDp(t.name)));
            for(String name : onlyUnits){
                UnitType vanilla = Vars.content.unit(name);
                if((vanilla == null || vanilla.minfo.mod != null) && !targets.contains(t -> t.name.equals(name) || stripDp(t.name).equals(name))){
                    warn("Unknown unit '@'", name);
                }
            }
        }else if(targets.isEmpty()){
            fail("Nothing to generate. Pass --assets <config/assets> and/or --units.");
        }

        for(UnitType type : targets){
            Seq<String> hints = new Seq<>();
            Pixmap full, ui;
            Pixmap[] wreckImages = null;

            if(render){
                UnitRenderer.Result result = UnitRenderer.render(type);
                for(String region : result.errors){
                    warn("@: draws the sprite '@', which doesn't exist in game. The client draws its 'error' (\"oh no\") sprite there " +
                    "and it is visible, so the icon shows it too. Fix the region in the unit/patch (for a missing '-outline': outline: false).", type.name, region);
                }
                if(result.hiddenErrors.size > 0){
                    Log.info("@: missing sprites drawn as the error sprite but completely covered by other sprites (harmless, " +
                    "also in game): @", type.name, result.hiddenErrors.toSeq().toString(", "));
                }
                if(result.clipped.size > 0){
                    Log.info("@: left out sprites drawn outside the unit's clip area (hidden parts): @", type.name,
                        result.clipped.keys().toSeq().map(k -> k + " at (" + result.clipped.get(k) + ")").toString(", "));
                }
                if(!result.unknown.isEmpty()) warn("@: ignored draws with unknown textures: @", type.name, result.unknown);
                if(result.image == null){
                    warn("@: the game draws nothing visible for this unit; skipped.", type.name);
                    continue;
                }
                full = result.image;
                if(verify){
                    written += write(full, type.name, type.name);
                    continue;
                }
                int maxd = Math.min(Math.max(full.width, full.height), UnitIconGenerator.maxUiIcon);
                ui = new Pixmap(maxd, maxd);
                GenAtlas.drawScaledFit(ui, full);
            }else{
                checkBodyRegion(type);
                fixPreviewRegion(type);
                fixRequiredRegions(type, hints);
                bindWeaponRegions(type, hints);
                UnitIconGenerator.Result result;
                try{
                    result = UnitIconGenerator.generate(type);
                }finally{
                    GenAtlas.clearOverrides();
                }
                if(result.ui == null){
                    warn("@: the vanilla generator produced no icon (see the error above); skipped.", type.name);
                    continue;
                }
                if(!type.drawBody && type.segments == 0){
                    warn("@: drawBody is false, so the vanilla generator draws no body. Nothing written (use --mode render).", type.name);
                    continue;
                }
                full = result.full;
                ui = result.ui;
                wreckImages = result.wrecks;
            }

            String base = stripDp(type.name);

            String uiName = target(type.uiIcon, "-ui", base + "-ui", "uiIcon", hints);
            written += write(ui, uiName, type.name);

            if(full == null){
                warn("@: generateFullIcon is false, so (like vanilla) only the ui icon was generated.", type.name);
                if(!hints.isEmpty()) patchHints.add("unit." + type.name + ": {\n" + hints.map(h -> "    " + h).toString("\n") + "\n}");
                continue;
            }

            String fullName = target(type.fullIcon, "-full", base + "-full", "fullIcon", hints);
            written += write(full, fullName, type.name);

            //vanilla: shadowRegion = fullIcon. The shadow is drawn tinted with Pal.shadow (rgb 0), so only alpha matters;
            //a copy of the full icon is the only image whose alpha matches it *after* the game antialiases it.
            String shadowRegion = regionName(type.shadowRegion);
            if(shadowRegion != null && shadowRegion.equals(regionName(type.fullIcon))){
                Log.info("@: shadowRegion already uses the full icon (vanilla default), no separate shadow written", type.name);
            }else{
                String shadowName = target(type.shadowRegion, "-shadow", base + "-shadow", "shadowRegion", hints);
                written += write(full, shadowName, type.name);
            }

            if(wrecks && wreckImages != null){
                StringBuilder wreckRegions = new StringBuilder("wreckRegions: [");
                for(int i = 0; i < 3; i++){
                    written += write(wreckImages[i], base + "-wreck" + i, type.name);
                    wreckRegions.append(i == 0 ? "" : ", ").append("dp-").append(base).append("-wreck").append(i);
                }
                hints.add(wreckRegions.append("]").toString());
            }

            Log.info("@: full @x@, ui @x@", type.name, full.width, full.height, ui.width, ui.height);

            if(!hints.isEmpty()){
                patchHints.add("unit." + type.name + ": {\n" + hints.map(h -> "    " + h).toString("\n") + "\n}");
            }
        }

        if(ingame != null){
            //only report sprites that were actually drawn
            ObjectSet<String> used = new ObjectSet<>();
            GenAtlas.ingameRegions.each((name, region) -> used.add(name));
            IngameAtlas atlas = ingame;
            Seq<String> jarUsed = atlas.fromJar.orderedKeys().select(used::contains);
            if(jarUsed.any()){
                Log.info("@ vanilla sprite(s) could not be reconstructed before antialiasing; their atlas pixels were used " +
                "(they end up antialiased twice):\n@", jarUsed.size, jarUsed.map(n -> "  " + n + ": " + atlas.fromJar.get(n)).toString("\n"));
            }
            ingame.jar.dispose();
        }

        Log.info("Wrote @ files.", written);

        if(!patchHints.isEmpty()){
            Log.warn("Suggested patch lines (so the game uses the generated images / draws what the icons show):\n\n@\n", patchHints.toString("\n"));
        }
        if(warnings > 0) Log.warn("@ warning(s).", warnings);
    }

    /** Loads config/assets the same way ServerControl.loadDataAssets + DataManager.load + DataPatcher.apply do on v160.7. */
    static void loadDataAssets(Seq<UnitType> targets, ObjectMap<UnitType, ContentAsset> unitAssets) throws IOException{
        Seq<DataAsset> dataAssets = new Seq<>();
        //special folder prefix for server-loaded content (affects the asset sort order)
        String prefix = "server-assets/";

        Fi contentFolder = assets.child(DataAssetType.content.folder);
        for(ContentType ctype : ContentAsset.loadableContent){
            Fi subfolder = contentFolder.child(ctype.folderName);
            if(!subfolder.exists()) continue;
            for(Fi file : subfolder.findAll(f -> DataAssetType.content.extensions.contains(f.extension().toLowerCase(Locale.ROOT)))){
                ContentAsset asset = (ContentAsset)DataAssetType.content.create();
                asset.readOverride(prefix + file.absolutePath().substring(subfolder.absolutePath().length() + 1), file, ctype);
                dataAssets.add(asset);
            }
        }

        Fi patchFolder = assets.child(DataAssetType.patch.folder);
        if(patchFolder.exists()){
            for(Fi file : patchFolder.findAll(f -> DataAssetType.patch.extensions.contains(f.extension().toLowerCase(Locale.ROOT)))){
                DataAsset asset = DataAssetType.patch.create();
                asset.readOverride(prefix + file.absolutePath().substring(patchFolder.absolutePath().length() + 1), file);
                dataAssets.add(asset);
            }
        }

        dataAssets.sort();

        Seq<ContentAsset> contents = dataAssets.select(a -> a instanceof ContentAsset).as();
        Seq<PatchAsset> patches = dataAssets.select(a -> a instanceof PatchAsset).as();

        Log.info("Loaded @ content and @ patch files from @", contents.size, patches.size, assets.absolutePath());

        //Patches may point icons/regions at *generated* vanilla atlas regions (e.g. unit-corvus-full). Those don't exist
        //as raw sprites; emulate them with the vanilla generator output (+ the antialiasing the pack step applies),
        //generated from the unpatched vanilla unit, since that is what the shipped atlas contains.
        ObjectSet<String> referenced = new ObjectSet<>();
        //(in render mode the in-game atlas already contains them)
        if(GenAtlas.ingame == null) for(DataAsset asset : dataAssets){
            String text = asset instanceof PatchAsset p ? p.patch : ((ContentAsset)asset).data;
            Matcher m = generatedIconRef.matcher(text);
            while(m.find()) referenced.add(m.group(0));
        }
        for(String ref : referenced){
            Matcher m = generatedIconRef.matcher(ref);
            if(!m.matches() || GenAtlas.contains(ref)) continue;
            UnitType vanilla = Vars.content.unit(m.group(1));
            if(vanilla == null || vanilla.minfo.mod != null) continue;

            UnitIconGenerator.Result result = UnitIconGenerator.generate(vanilla);
            Pixmap pix = m.group(2).equals("full") ? result.full : result.ui;
            if(pix == null) continue;
            Pixmaps.antialias(pix);
            GenAtlas.virtual(ref, () -> pix);
        }

        //apply exactly like a client does (headless=false => loadIcon()/load() run between init() and the patches)
        //on a client the renderer initializes the cache layers before any data patch is loaded (blocks need them)
        CacheLayer.init();
        Vars.state = new GameState();
        Vars.logicVars = new GlobalVars();
        DataPatcher patcher = new DataPatcher();
        LogHandler logger = Log.logger;
        //the patcher logs every warning and also stores it in the asset; report them once, below
        Log.logger = (level, text) -> {
            if(level == LogLevel.warn && text.contains("[ContentPatcher]")) return;
            logger.log(level, text);
        };
        Vars.headless = false;
        try{
            patcher.apply(patches, contents, false);
        }finally{
            Vars.headless = true;
            Log.logger = logger;
        }

        int hidden = 0;
        for(ContentAsset asset : contents){
            for(String w : asset.warnings){
                if(ignorable(w)) hidden++;
                else warn("[content @] @", asset.path, w);
            }
            if(asset.errored){
                warn("[content @] failed to load; skipped.", asset.path);
                continue;
            }
            if(asset.content instanceof UnitType type){
                targets.add(type);
                unitAssets.put(type, asset);
            }
        }
        for(PatchAsset asset : patches){
            for(String w : asset.warnings){
                if(ignorable(w)) hidden++;
                else warn("[patch @] @", asset.path, w);
            }
        }
        if(hidden > 0){
            Log.info("(@ data-patch warnings hidden: sprites that only exist in the packed game atlas - outlines, tread frames, " +
            "wrecks - and 'could not find an icon' notices. They don't affect icon generation.)", hidden);
        }
    }

    static final Pattern atlasOnlySprite = Pattern.compile("Sprite not found: '.*-(outline|wreck\\d+|treads\\d+-\\d+)'");

    static boolean ignorable(String warning){
        return atlasOnlySprite.matcher(warning).find() || warning.contains("Could not find an icon");
    }

    /** Picks the file name for an output: the dp- image the patch already references, or a default. */
    static String target(TextureRegion current, String suffix, String fallback, String field, Seq<String> hints){
        String name = regionName(current);
        if(name != null && name.startsWith(DataImagePacker.regionPrefix) && name.endsWith(suffix)){
            return name.substring(DataImagePacker.regionPrefix.length());
        }
        hints.add(field + ": " + DataImagePacker.regionPrefix + fallback);
        return fallback;
    }

    static boolean isGeneratedName(@Nullable String name){
        return name != null && (name.endsWith("-full") || name.endsWith("-ui") || name.endsWith("-outline") || name.endsWith("-shadow"));
    }

    static void checkBodyRegion(UnitType type){
        String name = regionName(type.region);
        if(isGeneratedName(name)){
            warn("@: region is '@', an already outlined/generated sprite; the vanilla generator will outline it again.", type.name, name);
        }
    }

    /**
     * previewRegion is only read by the icon generator (the game never draws it for units). Vanilla always resolves it
     * to the raw body sprite: {@code find(name + "-preview", name)}. Data patches usually only change {@code region},
     * leaving previewRegion pointing at a missing "dp-unit" sprite (vanilla would skip the unit), or point it at the
     * unit's own old icon (vanilla would outline an already outlined icon). In both cases resolve it the vanilla way
     * from the body region the unit actually uses.
     */
    static void fixPreviewRegion(UnitType type){
        String name = regionName(type.previewRegion);
        boolean valid = type.previewRegion != null && type.previewRegion.found();
        if(valid && !isGeneratedName(name)) return;

        String body = regionName(type.region);
        if(body == null || !type.region.found()){
            warn("@: previewRegion '@' is unusable and the body region '@' does not exist either.", type.name, name, body);
            return;
        }
        TextureRegion fixed = Core.atlas.find(body + "-preview", body);
        Log.info("@: previewRegion '@' is @ -> using '@' (vanilla default for body sprite '@')", type.name, name,
            valid ? "an already outlined icon" : "missing", ((AtlasRegion)fixed).name, body);
        type.previewRegion = fixed;
    }

    /**
     * Regions the vanilla generator requires (it skips the unit if they are missing) default to "&lt;unit name&gt;-base" etc.
     * When a patch swaps the body sprite but forgets these, the game can't find them either. Resolve them the vanilla
     * way from the body sprite the unit uses, and print the patch line that makes the game use the same sprite.
     */
    static void fixRequiredRegions(UnitType type, Seq<String> hints){
        String body = regionName(type.region);
        if(body == null || !type.region.found()) return;

        Unit sample = type.constructor.get();
        if(sample instanceof Mechc){
            type.baseRegion = fixRequired(type, type.baseRegion, "baseRegion", body, "-base", hints);
            type.legRegion = fixRequired(type, type.legRegion, "legRegion", body, "-leg", hints);
        }
        if(sample instanceof Tankc){
            type.treadRegion = fixRequired(type, type.treadRegion, "treadRegion", body, "-treads", hints);
        }
        if(type.drawCell){
            type.cellRegion = fixRequired(type, type.cellRegion, "cellRegion", body, "-cell", hints);
        }
    }

    static TextureRegion fixRequired(UnitType type, TextureRegion current, String field, String body, String suffix, Seq<String> hints){
        if(current != null && current.found()) return current;
        TextureRegion fixed = Core.atlas.find(body + suffix);
        if(!fixed.found()) return current;
        warn("@: @ '@' does not exist (in game either). Using '@', the vanilla default for body sprite '@'.",
            type.name, field, regionName(current), body + suffix, body);
        hints.add(field + ": " + body + suffix);
        return fixed;
    }

    /**
     * The vanilla generator re-runs {@code Weapon.load()}, i.e. looks weapon sprites up by weapon name. For vanilla
     * units that is exactly the region the game draws. Data-patch weapons are different: ContentParser renames them to
     * "dp-&lt;name&gt;" (so "spiroct-weapon" looks for a "dp-spiroct-weapon" sprite, which doesn't exist and the weapon is
     * invisible in game), and patches may set weapons.N.region directly. So while generating, make each weapon's name
     * resolve to the region the game actually draws (or to nothing), so the icon shows what the game shows.
     */
    static void bindWeaponRegions(UnitType type, Seq<String> hints){
        Seq<String> invisible = new Seq<>();
        for(int i = 0; i < type.weapons.size; i++){
            Weapon weapon = type.weapons.get(i);
            if(weapon.name == null || weapon.name.isEmpty()) continue;

            String region = weapon.region != null && weapon.region.found() ? regionName(weapon.region) : null;
            String cell = weapon.cellRegion != null && weapon.cellRegion.found() ? regionName(weapon.cellRegion) : null;

            GenAtlas.override(weapon.name, region);
            GenAtlas.override(weapon.name + "-preview", region != null && GenAtlas.contains(region + "-preview") ? region + "-preview" : null);
            GenAtlas.override(weapon.name + "-cell", cell);

            if(region == null){
                String plain = stripDp(weapon.name);
                if(!plain.equals(weapon.name) && GenAtlas.contains(plain)){
                    invisible.add(weapon.name);
                    hints.add("weapons." + i + ".region: " + plain);
                    boolean fixCell = cell == null && GenAtlas.contains(plain + "-cell");
                    if(fixCell) hints.add("weapons." + i + ".cellRegion: " + plain + "-cell");
                    if(GenAtlas.contains(plain + "-heat") && (weapon.heatRegion == null || !weapon.heatRegion.found())){
                        hints.add("weapons." + i + ".heatRegion: " + plain + "-heat");
                    }
                    if(applyHints){
                        //preview: generate as if the patch lines were applied
                        GenAtlas.override(weapon.name, plain);
                        GenAtlas.override(weapon.name + "-preview", GenAtlas.contains(plain + "-preview") ? plain + "-preview" : null);
                        if(fixCell) GenAtlas.override(weapon.name + "-cell", plain + "-cell");
                    }
                }
            }
        }
        if(!invisible.isEmpty()){
            String names = new ObjectSet<String>(){{ addAll(invisible); }}.toSeq().toString(", ");
            if(applyHints){
                Log.info("@: --apply-hints: drawing @ weapon(s) the game currently can't find (@) as if the weapons.N.region lines below were applied.", type.name, invisible.size, names);
            }else{
                warn("@: @ weapon(s) have no sprite in game (@) - data-patch weapon names get a 'dp-' prefix, so they are " +
                "invisible, and the icon shows them that way. Add the weapons.N.region lines below (or preview with --apply-hints).",
                type.name, invisible.size, names);
            }
        }
    }

    static @Nullable String regionName(@Nullable TextureRegion region){
        return region instanceof AtlasRegion a ? a.name : null;
    }

    static int write(@Nullable Pixmap pix, String fileName, @Nullable String unit){
        if(pix == null) return 0;
        Fi file;
        Fi existing = GenAtlas.file(DataImagePacker.regionPrefix + fileName);
        if(out != null){
            file = out.child(fileName + ".png");
        }else if(unit == null){
            //vanilla units (previews) never go into the data asset folder, which is shipped to clients
            file = Fi.get("build/vanilla-icons").child(fileName + ".png");
        }else if(existing != null){
            file = existing;
        }else{
            file = (assets != null ? assets.child("sprites").child("units") : Fi.get(".")).child(fileName + ".png");
        }
        file.parent().mkdirs();
        file.writePng(pix);
        Log.info("  -> @", file.path());
        return 1;
    }

    // ---- selftest ----

    static void selftest() throws IOException{
        Fi tmp = Fi.tempDirectory("icongen-atlas");
        ObjectMap<String, Region> regions = new ObjectMap<>();
        TextureAtlasData data;
        try(ZipFile zip = new ZipFile(selftestJar.file())){
            ZipEntry atlasEntry = zip.getEntry("sprites/sprites.aatls");
            if(atlasEntry == null) fail(selftestJar + " has no sprites/sprites.aatls; pass a Mindustry *client* jar.");
            for(ZipEntry entry : Collections.list(zip.entries())){
                String n = entry.getName();
                if(n.startsWith("sprites/") && n.indexOf('/', 8) == -1 && (n.endsWith(".png") || n.endsWith(".aatls"))){
                    try(InputStream in = zip.getInputStream(entry)){
                        tmp.child(n.substring(8)).write(in, false);
                    }
                }
            }
            data = new TextureAtlasData(tmp.child("sprites.aatls"), tmp, false);
        }
        for(Region r : data.getRegions()) regions.put(r.name, r);

        Seq<UnitType> units = Vars.content.units().select(t -> !(t.internal && !t.internalGenerateSprites));
        if(onlyUnits != null) units.retainAll(t -> onlyUnits.contains(t.name));

        //crop the needed atlas regions page by page (pages are large; keep only one in memory)
        ObjectMap<String, Pixmap> expected = new ObjectMap<>();
        for(AtlasPage page : data.getPages()){
            Pixmap pagePix = null;
            for(UnitType t : units){
                for(String name : new String[]{"unit-" + t.name + "-full", "unit-" + t.name + "-ui"}){
                    Region r = regions.get(name);
                    if(r == null || r.page != page) continue;
                    if(pagePix == null) pagePix = new Pixmap(page.textureFile);
                    int ow = r.originalWidth == 0 ? r.width : r.originalWidth, oh = r.originalHeight == 0 ? r.height : r.originalHeight;
                    int ox = (int)r.offsetX, oy = oh - r.height - (int)r.offsetY;
                    Pixmap pix = new Pixmap(ow, oh);
                    for(int y = 0; y < r.height; y++){
                        for(int x = 0; x < r.width; x++){
                            pix.setRaw(ox + x, oy + y, pagePix.getRaw(r.left + x, r.top + y));
                        }
                    }
                    expected.put(name, pix);
                }
            }
            if(pagePix != null) pagePix.dispose();
        }
        tmp.deleteDirectory();

        int ok = 0, bad = 0, missing = 0;
        for(UnitType t : units){
            UnitIconGenerator.Result result = UnitIconGenerator.generate(t);
            for(int i = 0; i < 2; i++){
                String name = "unit-" + t.name + (i == 0 ? "-full" : "-ui");
                Pixmap ours = i == 0 ? result.full : result.ui;
                Pixmap theirs = expected.get(name);
                if(ours == null || theirs == null){
                    if(ours != null || theirs != null){
                        Log.warn("  @: @", name, ours == null ? "not generated" : "not in atlas");
                        missing++;
                    }
                    continue;
                }
                //vanilla pack step: antialias every generated sprite
                Pixmap aa = ours.copy();
                Pixmaps.antialias(aa);
                String diff = compare(aa, theirs);
                if(diff == null){
                    ok++;
                }else{
                    bad++;
                    Log.err("  MISMATCH @: @", name, diff);
                    if(out != null){
                        out.child(name + "-generated.png").writePng(aa);
                        out.child(name + "-atlas.png").writePng(theirs);
                    }
                }
                aa.dispose();
                theirs.dispose();
            }
            if(result.full != null) result.full.dispose();
            if(result.ui != null) result.ui.dispose();
        }

        Log.info("Selftest: @ identical, @ different, @ missing (of @ units)", ok, bad, missing, units.size);
        if(bad > 0 || missing > 0) System.exit(1);
    }

    /** @return null if identical (fully transparent pixels compare equal regardless of their rgb). */
    static @Nullable String compare(Pixmap a, Pixmap b){
        if(a.width != b.width || a.height != b.height){
            return "size " + a.width + "x" + a.height + " vs atlas " + b.width + "x" + b.height;
        }
        int diffs = 0, maxDelta = 0;
        for(int y = 0; y < a.height; y++){
            for(int x = 0; x < a.width; x++){
                int ca = a.getRaw(x, y), cb = b.getRaw(x, y);
                if(ca == cb || ((ca & 0xff) == 0 && (cb & 0xff) == 0)) continue;
                diffs++;
                for(int s = 0; s < 32; s += 8){
                    maxDelta = Math.max(maxDelta, Math.abs(((ca >>> s) & 0xff) - ((cb >>> s) & 0xff)));
                }
            }
        }
        return diffs == 0 ? null : diffs + " pixels differ (max channel delta " + maxDelta + ")";
    }

    // ---- misc ----

    static String stripDp(String name){
        return name.startsWith(DataImagePacker.regionPrefix) ? name.substring(DataImagePacker.regionPrefix.length()) : name;
    }

    static void warn(String text, Object... args){
        warnings++;
        Log.warn(text, args);
    }

    static void fail(String message){
        System.err.println("error: " + message);
        System.exit(2);
    }

    static void parseArgs(String[] args){
        for(int i = 0; i < args.length; i++){
            String a = args[i];
            switch(a){
                case "--vanilla-sprites" -> vanillaSprites = Fi.get(next(args, ++i, a));
                case "--assets" -> assets = Fi.get(next(args, ++i, a));
                case "--out" -> out = Fi.get(next(args, ++i, a));
                case "--selftest" -> selftestJar = Fi.get(next(args, ++i, a));
                case "--client-jar" -> clientJar = Fi.get(next(args, ++i, a));
                case "--mode" -> {
                    mode = next(args, ++i, a);
                    if(!mode.equals("render") && !mode.equals("generator")) fail("--mode must be 'render' or 'generator'");
                }
                case "--wrecks" -> wrecks = true;
                case "--verify" -> verify = true;
                case "--apply-hints" -> applyHints = true;
                case "--units" -> {
                    onlyUnits = new ObjectSet<>();
                    for(String s : next(args, ++i, a).split(",")) if(!s.isBlank()) onlyUnits.add(s.trim());
                }
                case "-h", "--help" -> {
                    System.out.println("Usage: icongen --vanilla-sprites <Mindustry/core/assets-raw/sprites> [--assets <config/assets>] " +
                    "[--client-jar <Mindustry.jar>] [--mode render|generator] [--units a,b] [--out <dir>] [--apply-hints] [--wrecks] " +
                    "[--verify] [--selftest <Mindustry.jar>]");
                    System.exit(0);
                }
                default -> fail("unknown argument: " + a);
            }
        }
        if(vanillaSprites == null || !vanillaSprites.isDirectory()) fail("--vanilla-sprites <dir> is required and must exist");
        if(assets != null && !assets.isDirectory()) fail("--assets folder does not exist: " + assets);
        if(selftestJar != null && !selftestJar.exists()) fail("--selftest jar does not exist: " + selftestJar);
        if(clientJar != null && !clientJar.exists()) fail("--client-jar does not exist: " + clientJar);
        if(verify && out == null) fail("--verify needs --out");
        if(mode.equals("render") && applyHints) fail("--apply-hints only applies to --mode generator");
        if(mode.equals("render") && wrecks) fail("--wrecks only applies to --mode generator");
    }

    static String next(String[] args, int i, String flag){
        if(i >= args.length) fail(flag + " needs a value");
        return args[i];
    }
}

package rvsb.icongen;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.icongen.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;

/**
 * Renders a unit with the game's own drawing code ({@link UnitType#draw(Unit)}) into a pixmap via {@link SoftBatch}.
 *
 * The unit is drawn the way the game draws a unit that is not in the world - a payload / the unit in a reconstructor:
 * facing up, at rest (no warmup, recoil, heat, walk cycle), with full health. In that mode the game itself skips the
 * shadow, legs, lights, abilities, mining/building beams and the fog check. On top of that, for icon purposes:
 * <ul>
 *     <li>skipped: soft shadow, engines and trails (glow effects; vanilla icons don't have them either) and weapon
 *     shadows ({@code circle-shadow});</li>
 *     <li>cell regions use the vanilla icon cell palette (exactly like the vanilla generator) instead of a team tint.</li>
 * </ul>
 * Everything else - body, outlines, weapons, all {@code DrawPart}s with their layers, mirroring, scaling, rotation,
 * colours, blending, and sprites that a patch swapped for vanilla ones - is whatever the game code draws.
 */
public class UnitRenderer{
    static boolean initialized;
    /** Verification only: keep team-tinted cells and weapon shadows, i.e. draw exactly what the game draws. */
    public static boolean verify;

    static void init(){
        if(initialized) return;
        initialized = true;
        //Unit.draw checks inFogTo(player.team()): same team => never fogged
        Vars.player = Player.create();
        Vars.player.team(Team.sharded);
        //mech units read the floor they stand on (no world => air)
        if(Vars.world == null) Vars.world = new World();
    }

    public static class Result{
        public @Nullable Pixmap image;
        /** missing regions whose error sprite is visible in the image */
        public OrderedSet<String> errors = new OrderedSet<>();
        /** missing regions that are drawn (as the error sprite) but completely covered */
        public OrderedSet<String> hiddenErrors = new OrderedSet<>();
        public OrderedSet<String> unknown = new OrderedSet<>();
        public OrderedMap<String, String> clipped = new OrderedMap<>();
    }

    public static Result render(UnitType type){
        init();
        Result result = new Result();

        Unit unit = type.create(Team.sharded);
        unit.set(0f, 0f);
        unit.rotation = 90f;
        if(unit instanceof Mechc mech) mech.baseRotation(90f);

        //icon-irrelevant visuals off
        boolean softShadow = type.drawSoftShadow;
        Seq<UnitType.UnitEngine> engines = type.engines;
        int trail = type.trailLength;
        type.drawSoftShadow = false;
        type.engines = new Seq<>();
        type.trailLength = 0;

        //cells: vanilla icon palette
        TextureRegion cell = type.cellRegion;
        ObjectMap<Weapon, TextureRegion> weaponCells = new ObjectMap<>();
        if(cell instanceof GenRegion g && !verify) type.cellRegion = GenAtlas.cellVariant(g);
        for(Weapon weapon : type.weapons){
            if(weapon.cellRegion instanceof GenRegion g && !verify){
                weaponCells.put(weapon, weapon.cellRegion);
                weapon.cellRegion = GenAtlas.cellVariant(g);
            }
        }

        SoftBatch batch = new SoftBatch();
        if(!verify) batch.skipped.add("circle-shadow");
        if(type.softShadowRegion instanceof GenRegion g) batch.skipped.add(g.name);

        Batch prev = Core.batch;
        Core.batch = batch;
        try{
            Draw.reset();
            Draw.z(Layer.groundUnit);
            type.draw(unit);

            boolean oddX = false, oddY = false;
            if(type.region instanceof GenRegion g && g.pixmap != null){
                oddX = g.width % 2 == 1;
                oddY = g.height % 2 == 1;
            }
            result.image = batch.render(Draw.scl, oddX, oddY, type.clipSize);
            result.clipped.putAll(batch.clipped);
            result.errors.addAll(batch.visibleErrors);
            result.hiddenErrors.addAll(batch.errors);
            result.hiddenErrors.removeAll(batch.visibleErrors.toSeq());
            result.unknown.addAll(batch.unknownTextures);
        }finally{
            Draw.reset();
            Core.batch = prev;
            Draw.xscl = Draw.yscl = 1f;
            type.drawSoftShadow = softShadow;
            type.engines = engines;
            type.trailLength = trail;
            type.cellRegion = cell;
            weaponCells.each((weapon, region) -> weapon.cellRegion = region);
        }
        return result;
    }
}

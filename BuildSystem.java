package zombiemode.core;

import arc.*;
import arc.graphics.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;

import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.net.Administration.ActionType;
import mindustry.net.NetConnection;
import mindustry.type.Item;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.payloads.Payload;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.modules.ItemModule;

import zombiemode.ZombiePlugin;

import static mindustry.Vars.*;

/**
 * Coreless, per-player build economy (Project Zomboid style).
 *
 * Each player builds out of their OWN inventory:
 *   - the item stack carried by their unit,
 *   - containers/vaults carried as payloads inside their unit,
 *   - containers/vaults within {@link #reachTiles} tiles of their unit.
 *
 * == Why a custom build driver? ==
 * Vanilla building (mindustrysrc/.../world/blocks/ConstructBlock.java and
 * mindustrysrc/.../entities/comp/BuilderComp.java) is core-driven:
 * BuilderComp.updateBuildLogic() bails out with `if(core == null && !infinite) return;`
 * and ConstructBuild.construct(builder, core, amount, config) pays for progress out of
 * core.items. With no server-side core (this mode has none), units never build.
 *
 * So this system re-implements updateBuildLogic() step for step on the server, but
 * instead of a team core it passes a detached proxy CoreBuild whose ItemModule is
 * loaded with the player's pooled inventory. construct()/deconstruct() then run the
 * EXACT vanilla math (accumulator, totalAccumulator, itemsLeft, gradual progress,
 * refunds), and afterwards the item delta on the proxy is settled against the real
 * sources (unit stack first, then stores). Construction is therefore gradual,
 * cancellable, resumable, and refunds correctly - true vanilla behaviour, no instant
 * builds.
 *
 * == The fake client-only core ==
 * Clients also refuse to build (and have no resource display) without a core. Each
 * client therefore gets a FAKE core that exists only on that client, never on the
 * server. It is created with a targeted SetTileCallPacket (same technique as
 * ChunkStreamer) in a reserved map corner, and its item counts are pushed with
 * targeted SetTileItemsCallPacket, which the client resolves by position - no server
 * building required (see InputHandler.setTileItems). Because the vanilla core item
 * display reads the client's own core, every player sees a live, personal resource
 * counter through the aggressive vanilla core item pipeline - no HUD popups.
 * The client also predicts building against this core, which keeps the build UI
 * (red/white affordability, beams, ghosts) fully functional.
 *
 * ChunkStreamer is told to never touch the reserved corner (clientOnlyTiles) and
 * re-creates the core after /sync via its worldResent hook.
 *
 * Start with: new BuildSystem().start();
 */
public class BuildSystem{

    // ---- config ----
    /** build reach, in tiles, for nearby containers/vaults. */
    static final int reachTiles = 10;
    /** block used for the client-only fake core. */
    static final Block coreBlock = Blocks.coreShard;
    /** centre tile of the client-only core (map corner). */
    static final int coreX = 2, coreY = 2;
    /** half-size of the reserved no-build square around the fake core. */
    static final int reservedRad = 4;
    /** ticks between item pushes to the client core (corrects client prediction drain). */
    static final float itemSyncInterval = 30f;
    /** ticks between full re-sends of the client core itself (robustness). */
    static final float coreRefreshInterval = 300f;
    /** colour of the effect played on stores that items were taken from. */
    static final Color usedColor = Color.valueOf("8cf2ff");

    /** containers/vaults we treat as valid personal item sources. */
    static boolean isStore(Block b){
        return b == Blocks.container || b == Blocks.vault
            || b == Blocks.reinforcedContainer || b == Blocks.reinforcedVault;
    }

    static class PlayerState{
        float buildCounter;
        float itemTimer = Mathf.random(itemSyncInterval); //stagger pushes across players
        float refreshTimer;
        boolean corePlaced;
    }

    final ObjectMap<Player, PlayerState> states = new ObjectMap<>();
    final ObjectMap<Building, Long> lastUsedFx = new ObjectMap<>();
    final ObjectSet<Building> seen = new ObjectSet<>();

    /** detached CoreBuild used purely as an inventory vessel for vanilla construct(). */
    CoreBuild proxy;

    public void start(){
        //re-place the client core after ChunkStreamer resends a fresh world (/sync)
        ChunkStreamer.worldResent = player -> {
            PlayerState st = states.get(player);
            if(st != null) st.corePlaced = false;
        };

        Events.on(WorldLoadEvent.class, e -> {
            states.clear();
            lastUsedFx.clear();
            //give the world a moment to finish initialising before editing it
            Timer.schedule(this::setupWorld, 1f);
        });

        Events.on(PlayerJoin.class, e -> states.put(e.player, new PlayerState()));
        Events.on(PlayerLeave.class, e -> states.remove(e.player));

        //nothing may ever be placed on the reserved corner server-side, or chunk
        //loading would overwrite the client-only cores
        netServer.admins.addActionFilter(action ->
            !(action.type == ActionType.placeBlock && action.tile != null
                && inReserved(action.tile.x, action.tile.y)));

        Events.run(Trigger.update, this::update);

        Log.info("[BuildSystem] started (reach=@ tiles, client core=@ at @,@)",
            reachTiles, coreBlock.name, coreX, coreY);
    }

    // ------------------------------------------------------------------ world setup

    void setupWorld(){
        if(!state.isGame()) return;

        //coreless mode: silently remove every server-side core. kill() would explode,
        //and any surviving server core would both re-enable the vanilla builder loop
        //(with the wrong, shared economy) and leak into chunk streaming.
        Seq<Tile> stale = new Seq<>();
        for(Building b : Groups.build){
            if(b instanceof CoreBuild) stale.add(b.tile);
        }
        stale.each(t -> t.setNet(Blocks.air));

        //reserve the corner so ChunkStreamer never sends anything that would
        //overwrite the client-only cores
        ChunkStreamer.clientOnlyTiles.clear();
        Tile centre = world.tile(coreX, coreY);
        if(centre != null){
            centre.getLinkedTilesAs(coreBlock, t -> ChunkStreamer.clientOnlyTiles.add(t.pos()));
        }

        //vanilla economy rules. with no server cores and finite resources, vanilla
        //BuilderComp disables itself ((core == null && !infinite) -> return), which
        //is exactly what lets the driver below take over. debug mode keeps freebuild.
        state.rules.infiniteResources = Core.settings.getBool(ZombiePlugin.debugKey, false);
        state.rules.buildSpeedMultiplier = 1f;
        Call.setRules(state.rules);
    }

    // ------------------------------------------------------------------ update

    void update(){
        if(!state.isGame() || state.isPaused() || world.tiles == null) return;

        for(Player player : Groups.player){
            if(player.con == null || !player.con.isConnected()) continue;

            PlayerState st = states.get(player);
            if(st == null) states.put(player, st = new PlayerState()); //map change keeps players

            //server-authoritative vanilla build driver
            Unit unit = player.unit();
            if(unit != null && !player.dead()){
                updateBuilder(player, st, unit);
            }

            //client-only core upkeep
            if(!st.corePlaced || (st.refreshTimer += Time.delta) >= coreRefreshInterval){
                st.refreshTimer = 0f;
                placeClientCore(player, st);
            }else if((st.itemTimer += Time.delta) >= itemSyncInterval){
                st.itemTimer = 0f;
                pushItems(player, gather(player));
            }
        }
    }

    // ------------------------------------------------------------------ build driver
    // mirrors BuilderComp.updateBuildLogic(), which is dead on the server in this mode

    void updateBuilder(Player player, PlayerState st, Unit unit){
        //debug freebuild: the vanilla loop is alive (infinite resources), don't double-build
        if(state.rules.infiniteResources) return;
        if(unit.type.buildSpeed <= 0f || !unit.canBuild() || !unit.updateBuilding) return;

        unit.validatePlans();
        if(unit.plans.isEmpty()) return;

        st.buildCounter += Time.delta;
        if(Float.isNaN(st.buildCounter) || Float.isInfinite(st.buildCounter)) st.buildCounter = 0f;
        st.buildCounter = Math.min(st.buildCounter, 10f);

        int count = 0;
        while(st.buildCounter >= 1f && count++ < 10 && unit.plans.size > 0){
            st.buildCounter -= 1f;
            step(player, unit);
        }
    }

    void step(Player player, Unit unit){
        BuildPlan plan = unit.buildPlan();
        if(plan == null) return;

        Tile tile = world.tile(plan.x, plan.y);
        if(tile == null){
            unit.plans.removeFirst();
            return;
        }

        //vanilla: wait until the unit is in build range
        if(!unit.within(tile.worldx(), tile.worldy(), unit.type.buildRange)) return;

        //keep the client-only core corner clear
        if(!plan.breaking && overlapsReserved(plan)){
            unit.plans.removeFirst();
            return;
        }

        Res res = gather(player);

        if(!(tile.build instanceof ConstructBuild)){
            if(!plan.initialized && !plan.breaking
                && Build.validPlaceIgnoreUnits(plan.block, unit.team, plan.x, plan.y, plan.rotation, true, true)){

                if(!Build.checkNoUnitOverlap(plan.block, plan.x, plan.y)){
                    //a unit is in the way; rotate the plan to the back, like vanilla
                    unit.plans.addLast(unit.plans.removeFirst());
                    return;
                }

                //vanilla gate (BuilderComp 'hasAll'): at least 1 of each required item
                boolean hasAll = !Structs.contains(plan.block.requirements,
                    s -> res.available.get(s.item, 0) < Math.min(Mathf.round(s.amount * state.rules.buildCostMultiplier), 1));

                if(hasAll){
                    Call.beginPlace(unit, plan.block, unit.team, plan.x, plan.y, plan.rotation,
                        plan.block.instantBuild ? plan.config : null);

                    if(plan.block.instantBuild){
                        //vanilla never charges these through construct(); charge directly
                        if(tile.block() == plan.block){
                            for(var s : plan.block.requirements){
                                res.take(s.item, Math.max(0, Mathf.round(s.amount * state.rules.buildCostMultiplier)));
                            }
                        }
                        unit.plans.removeFirst();
                        return;
                    }
                }else{
                    plan.stuck = true;
                    unit.plans.addLast(unit.plans.removeFirst());
                    return;
                }
            }else if(!plan.initialized && plan.breaking && Build.validBreak(unit.team, plan.x, plan.y)){
                Call.beginBreak(unit, unit.team, plan.x, plan.y);
            }else{
                unit.plans.removeFirst();
                return;
            }
        }else if((tile.team() != unit.team && tile.team() != Team.derelict)
            || (!plan.breaking && (((ConstructBuild)tile.build).current != plan.block || tile.build.tile != tile))){
            //someone else's construction, or a different block: drop the plan, like vanilla
            unit.plans.removeFirst();
            return;
        }

        if(!(tile.build instanceof ConstructBuild entity)) return;

        if(!plan.initialized){
            Events.fire(new BuildSelectEvent(tile, unit.team, unit, plan.breaking));
            plan.initialized = true;
        }

        float bs = 1f / entity.buildCost * unit.type.buildSpeed * unit.buildSpeedMultiplier * state.rules.buildSpeed(unit.team);

        //run the EXACT vanilla construction math against this player's pooled
        //inventory by lending it to a detached proxy core for the duration of the call
        CoreBuild core = proxy(unit.team, res);

        if(plan.breaking){
            entity.deconstruct(unit, core, bs);
        }else{
            entity.construct(unit, core, bs, plan.config);
        }

        //settle whatever construct()/deconstruct() did to the proxy against the
        //real sources: consumption is charged, refunds are returned
        settle(res, core);

        plan.stuck = Mathf.equal(plan.progress, entity.progress);
        plan.progress = entity.progress;
    }

    CoreBuild proxy(Team team, Res res){
        if(proxy == null){
            proxy = (CoreBuild)coreBlock.newBuilding();
            proxy.items = new ItemModule();
            proxy.storageCapacity = 1_000_000; //deconstruct() refund headroom
        }
        proxy.team = team;
        proxy.items.clear();
        for(ObjectIntMap.Entry<Item> e : res.available){
            proxy.items.set(e.key, e.value);
        }
        return proxy;
    }

    void settle(Res res, CoreBuild core){
        for(int i = 0; i < content.items().size; i++){
            Item item = content.items().get(i);
            int before = res.available.get(item, 0);
            int after = core.items.get(item);
            if(after < before){
                res.take(item, before - after);  //construction consumed items
            }else if(after > before){
                res.give(item, after - before);  //deconstruction refunded items
            }
        }
    }

    // ------------------------------------------------------------------ inventory

    /** Snapshot of every item source available to a player right now. */
    class Res{
        final Unit unit;
        final ObjectIntMap<Item> available = new ObjectIntMap<>();
        final Seq<Building> stores = new Seq<>();

        Res(Unit unit){ this.unit = unit; }

        /** Removes items from the real sources: unit stack first, then stores. */
        void take(Item item, int amount){
            if(amount <= 0) return;

            if(unit != null && unit.stack != null && unit.stack.item == item && unit.stack.amount > 0){
                int t = Math.min(amount, unit.stack.amount);
                unit.stack.amount -= t;
                amount -= t;
            }

            for(int i = 0; i < stores.size && amount > 0; i++){
                Building b = stores.get(i);
                if(b.items == null) continue;
                int t = Math.min(amount, b.items.get(item));
                if(t > 0){
                    b.items.remove(item, t);
                    amount -= t;
                    markUsed(b);
                }
            }
        }

        /** Returns refunded items to the real sources: stores first, then the unit. */
        void give(Item item, int amount){
            if(amount <= 0) return;

            for(int i = 0; i < stores.size && amount > 0; i++){
                Building b = stores.get(i);
                if(b.items == null) continue;
                int space = b.block.itemCapacity - b.items.total();
                if(space <= 0) continue;
                int t = Math.min(amount, space);
                b.items.add(item, t);
                amount -= t;
                markUsed(b);
            }

            if(amount > 0 && unit != null && unit.stack != null
                && (unit.stack.amount == 0 || unit.stack.item == item)){
                int t = Math.min(amount, unit.itemCapacity() - unit.stack.amount);
                if(t > 0){
                    unit.stack.item = item;
                    unit.stack.amount += t;
                }
            }
        }
    }

    Res gather(Player player){
        Unit unit = player.unit();
        Res res = new Res(unit);
        if(unit == null || player.dead()) return res;

        //the unit's own carried stack
        if(unit.stack != null && unit.stack.amount > 0){
            add(res, unit.stack.item, unit.stack.amount);
        }

        //containers/vaults carried as payloads inside the unit
        if(unit instanceof Payloadc pay){
            for(Payload p : pay.payloads()){
                if(p instanceof BuildPayload bp && bp.build != null
                    && isStore(bp.build.block) && bp.build.items != null){
                    addStore(res, bp.build);
                }
            }
        }

        //containers/vaults within reach of the unit
        float range = reachTiles * tilesize;
        int tx = unit.tileX(), ty = unit.tileY();
        seen.clear();
        for(int dy = -reachTiles; dy <= reachTiles; dy++){
            for(int dx = -reachTiles; dx <= reachTiles; dx++){
                Tile t = world.tile(tx + dx, ty + dy);
                if(t == null || t.build == null) continue;
                Building b = t.build;
                if(b.team != unit.team || !isStore(b.block) || b.items == null) continue;
                if(!seen.add(b)) continue;            //multiblock dedupe
                if(unit.dst(b) > range) continue;     //true radial distance
                addStore(res, b);
            }
        }

        return res;
    }

    void addStore(Res res, Building b){
        res.stores.add(b);
        for(int i = 0; i < content.items().size; i++){
            Item it = content.items().get(i);
            int amt = b.items.get(it);
            if(amt > 0) add(res, it, amt);
        }
    }

    static void add(Res res, Item item, int amount){
        res.available.put(item, res.available.get(item, 0) + amount);
    }

    // ------------------------------------------------------------------ client core

    void placeClientCore(Player player, PlayerState st){
        Tile tile = world.tile(coreX, coreY);
        if(tile == null || player.con == null || !player.con.isConnected()) return;

        //client-only: a targeted SetTile creates the core on this client and
        //nowhere else. the server world stays air here.
        ChunkStreamer.sendSetTile(player.con, tile, coreBlock, player.team(), 0);
        st.corePlaced = true;

        pushItems(player, gather(player));
    }

    /**
     * Pushes this player's pooled inventory into their client-only core.
     * setTileItems resolves the building by POSITION on the client, so no
     * server-side building is needed. Counts are absolute, which also corrects
     * the drain the client's own build prediction applies to its fake core.
     */
    void pushItems(Player player, Res res){
        Tile tile = world.tile(coreX, coreY);
        if(tile == null || player.con == null || !player.con.isConnected()) return;

        int[] pos = {tile.pos()};
        for(int i = 0; i < content.items().size; i++){
            Item item = content.items().get(i);
            sendSetTileItems(player.con, item, res.available.get(item, 0), pos);
        }
    }

    static void sendSetTileItems(NetConnection con, Item item, int amount, int[] positions){
        if(con == null || !con.isConnected()) return;
        SetTileItemsCallPacket packet = new SetTileItemsCallPacket();
        packet.item = item;
        packet.amount = amount;
        packet.positions = positions;
        con.send(packet, true);
    }

    // ------------------------------------------------------------------ misc

    static boolean inReserved(int x, int y){
        return x >= coreX - reservedRad && x <= coreX + reservedRad
            && y >= coreY - reservedRad && y <= coreY + reservedRad;
    }

    static boolean overlapsReserved(BuildPlan plan){
        int s = plan.block.size;
        int ox = plan.x - (s - 1) / 2, oy = plan.y - (s - 1) / 2;
        return ox <= coreX + reservedRad && ox + s - 1 >= coreX - reservedRad
            && oy <= coreY + reservedRad && oy + s - 1 >= coreY - reservedRad;
    }

    void markUsed(Building b){
        long now = Time.millis();
        Long last = lastUsedFx.get(b);
        if(last != null && now - last < 250) return; //don't spam
        lastUsedFx.put(b, now);
        Call.effect(Fx.mineBig, b.x, b.y, 0f, usedColor);
    }
}

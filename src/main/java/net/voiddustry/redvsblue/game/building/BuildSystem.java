package net.voiddustry.redvsblue.game.building;

import arc.Events;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.util.Log;
import arc.util.Structs;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.SetTileCallPacket;
import mindustry.gen.SetTileItemsCallPacket;
import mindustry.gen.Unit;
import mindustry.net.NetConnection;
import mindustry.type.Category;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.modules.ItemModule;
import net.voiddustry.redvsblue.PlayerData;
import net.voiddustry.redvsblue.game.stations.ArmorWorkbench;
import net.voiddustry.redvsblue.game.stations.Booster;
import net.voiddustry.redvsblue.game.stations.Laboratory;
import net.voiddustry.redvsblue.game.stations.Miner;
import net.voiddustry.redvsblue.game.stations.Recycler;
import net.voiddustry.redvsblue.game.stations.RepairPoint;
import net.voiddustry.redvsblue.game.stations.SuppressorTower;

import static net.voiddustry.redvsblue.RedVsBluePlugin.players;

/**
 * Points-driven build economy for blue players.
 *
 * == Why a custom build driver? ==
 * Vanilla building (ConstructBlock / BuilderComp.updateBuildLogic()) is core-driven:
 * BuilderComp bails out with `if(core == null && !infinite) return;` and construct()
 * pays for progress out of core.items. Blue has no server-side cores (they are killed
 * on world load), so vanilla units never build.
 *
 * This system re-implements updateBuildLogic() step for step on the server, but
 * instead of a team core it passes a detached proxy CoreBuild whose ItemModule is
 * loaded with the player's points as {@link Items#dormantCyst} (patched to display
 * as "Points"). construct()/deconstruct() then run the EXACT vanilla math
 * (accumulator, totalAccumulator, itemsLeft, gradual progress), and afterwards the
 * item delta on the proxy is settled against PlayerData.score. Construction is
 * therefore gradual, cancellable and resumable - no instant builds.
 *
 * The economy is points ONLY: no items are ever taken from unit stacks, payloads
 * or nearby containers.
 *
 * Refund rules:
 * - deconstructing a FINISHED block refunds points (vanilla refund ratio),
 * - cancelling/breaking an unfinished construction refunds nothing,
 * - a construction destroyed by damage refunds nothing.
 *
 * == The fake client-only core ==
 * Clients also refuse to build (and have no resource display) without a core. Each
 * blue client therefore gets a FAKE core that exists only on that client, never on
 * the server. It is created with a targeted SetTileCallPacket in the top-left corner
 * of the background-logic strip (see MapExpander), and its item count is pushed with
 * targeted SetTileItemsCallPacket, which the client resolves by position - no server
 * building required (see InputHandler.setTileItems). Because the vanilla core item
 * display reads the client's own core, every player sees a live, personal points
 * counter, and the client predicts building against this core, which keeps the build
 * UI (red/white affordability, beams, ghosts) fully functional. Absolute counts are
 * re-pushed periodically, which also corrects the client's prediction drain.
 */
public class BuildSystem {

    /** block used for the client-only fake core. */
    static final Block coreBlock = Blocks.coreShard;
    /** ticks between points pushes to the client core (corrects client prediction drain). */
    static final float itemSyncInterval = 30f;
    /** ticks between full re-sends of the client core itself (robustness, covers /sync). */
    static final float coreRefreshInterval = 300f;

    static class PlayerState {
        float buildCounter;
        float itemTimer = Mathf.random(itemSyncInterval); //stagger pushes across players
        float refreshTimer;
        boolean corePlaced;
    }

    static final ObjectMap<Player, PlayerState> states = new ObjectMap<>();

    /** detached CoreBuild used purely as an inventory vessel for vanilla construct(). */
    static CoreBuild proxy;

    private BuildSystem() {
    }

    public static void init() {
        Events.on(EventType.WorldLoadEvent.class, e -> states.clear());

        Events.on(EventType.PlayerJoin.class, e -> states.put(e.player, new PlayerState()));
        Events.on(EventType.PlayerLeave.class, e -> states.remove(e.player));

        Events.run(EventType.Trigger.update, BuildSystem::update);

        Log.info("[BuildSystem] started (points economy, client core=@ on strip top-left)", coreBlock.name);
    }

    /** centre tile of the client-only core: top-left corner of the background strip. */
    static Tile clientCoreTile() {
        //coreShard is 3x3; centre (1, height - 2) occupies x 0..2, y height-3..height-1
        return Vars.world.tile(1, Vars.world.height() - 2);
    }

    // ------------------------------------------------------------------ update

    static void update() {
        if (!Vars.state.isGame() || Vars.state.isPaused() || Vars.world.tiles == null) return;

        for (Player player : Groups.player) {
            if (player.con == null || !player.con.isConnected()) continue;

            PlayerState st = states.get(player);
            if (st == null) states.put(player, st = new PlayerState()); //map change keeps players

            //server-authoritative vanilla build driver, blue only
            if (player.team() == Team.blue) {
                Unit unit = player.unit();
                if (unit != null && !player.dead()) {
                    updateBuilder(st, player, unit);
                }
            }

            //client-only core upkeep
            updateClientCore(player, st);
        }
    }

    // ------------------------------------------------------------------ build driver
    // mirrors BuilderComp.updateBuildLogic(), which is dead on the server in this mode

    static void updateBuilder(PlayerState st, Player player, Unit unit) {
        if (Vars.state.rules.infiniteResources) return;
        if (unit.type.buildSpeed <= 0f || !unit.canBuild() || !unit.updateBuilding) return;

        unit.validatePlans();
        if (unit.plans.isEmpty()) return;

        st.buildCounter += Time.delta;
        if (Float.isNaN(st.buildCounter) || Float.isInfinite(st.buildCounter)) st.buildCounter = 0f;
        st.buildCounter = Math.min(st.buildCounter, 10f);

        int count = 0;
        while (st.buildCounter >= 1f && count++ < 10 && unit.plans.size > 0) {
            st.buildCounter -= 1f;
            step(player, unit);
        }
    }

    static void step(Player player, Unit unit) {
        BuildPlan plan = unit.buildPlan();
        if (plan == null) return;

        Tile tile = Vars.world.tile(plan.x, plan.y);
        if (tile == null) {
            unit.plans.removeFirst();
            return;
        }

        //vanilla: wait until the unit is in build range
        if (!unit.within(tile.worldx(), tile.worldy(), unit.type.buildRange)) return;

        PlayerData data = players.get(player.uuid());
        if (data == null) return;

        if (!(tile.build instanceof ConstructBuild)) {
            if (!plan.initialized && !plan.breaking
                && Build.validPlaceIgnoreUnits(plan.block, unit.team, plan.x, plan.y, plan.rotation, true, true)
                && plan.block.environmentBuildable() && plan.block.isPlaceable()) {

                if (!Build.checkNoUnitOverlap(plan.block, plan.x, plan.y)) {
                    //a unit is in the way; rotate the plan to the back, like vanilla
                    unit.plans.addLast(unit.plans.removeFirst());
                    return;
                }

                //station blocks stay INSTANT purchases; the buy methods charge points
                //and finish construction themselves
                if (plan.block.category == Category.logic) {
                    buyStation(player, plan.block, tile);
                    unit.plans.removeFirst();
                    return;
                }

                //vanilla gate (BuilderComp 'hasAll'): at least 1 of each required item
                boolean hasAll = !Structs.contains(plan.block.requirements,
                    s -> available(data, s.item) < Math.min(Mathf.round(s.amount * Vars.state.rules.buildCostMultiplier), 1));

                if (hasAll) {
                    Call.beginPlace(unit, plan.block, unit.team, plan.x, plan.y, plan.rotation,
                        plan.block.instantBuild ? plan.config : null);

                    if (plan.block.instantBuild) {
                        //vanilla never charges these through construct(); charge directly
                        if (tile.block() == plan.block) {
                            for (var s : plan.block.requirements) {
                                if (s.item == Items.dormantCyst) {
                                    data.subtractScore(Math.max(0, Mathf.round(s.amount * Vars.state.rules.buildCostMultiplier)));
                                }
                            }
                        }
                        unit.plans.removeFirst();
                        return;
                    }
                } else {
                    plan.stuck = true;
                    unit.plans.addLast(unit.plans.removeFirst());
                    return;
                }
            } else if (!plan.initialized && plan.breaking && Build.validBreak(unit.team, plan.x, plan.y)) {
                Call.beginBreak(unit, unit.team, plan.x, plan.y);
            } else {
                unit.plans.removeFirst();
                return;
            }
        } else if ((tile.team() != unit.team && tile.team() != Team.derelict)
            || (!plan.breaking && (((ConstructBuild) tile.build).current != plan.block || tile.build.tile != tile))) {
            //someone else's construction, or a different block: drop the plan, like vanilla
            unit.plans.removeFirst();
            return;
        }

        if (!(tile.build instanceof ConstructBuild entity)) return;

        if (!plan.initialized) {
            Events.fire(new EventType.BuildSelectEvent(tile, unit.team, unit, plan.breaking));
            plan.initialized = true;
        }

        float bs = 1f / entity.buildCost * unit.type.buildSpeed * unit.buildSpeedMultiplier * Vars.state.rules.buildSpeed(unit.team);

        //run the EXACT vanilla construction math against this player's points by
        //lending them to a detached proxy core for the duration of the call
        int before = Math.max(data.getScore(), 0);
        CoreBuild core = proxy(unit.team, before);

        boolean allowRefund = false;
        if (plan.breaking) {
            //only deconstructing a FINISHED block refunds points (setDeconstruct sets
            //current == previous); cancelling an unfinished construction refunds nothing
            allowRefund = entity.current == entity.previous;
            entity.deconstruct(unit, core, bs);
        } else {
            entity.construct(unit, core, bs, plan.config);
        }

        //settle whatever construct()/deconstruct() did to the proxy against the score:
        //consumption is always charged, refunds only when allowed
        settle(data, before, allowRefund);

        plan.stuck = Mathf.equal(plan.progress, entity.progress);
        plan.progress = entity.progress;
    }

    static CoreBuild proxy(Team team, int points) {
        if (proxy == null) {
            proxy = (CoreBuild) coreBlock.newBuilding();
            proxy.items = new ItemModule();
            proxy.storageCapacity = 1_000_000; //deconstruct() refund headroom
        }
        proxy.team = team;
        proxy.items.clear();
        proxy.items.set(Items.dormantCyst, points);
        return proxy;
    }

    static void settle(PlayerData data, int before, boolean allowRefund) {
        int after = proxy.items.get(Items.dormantCyst);
        if (after < before) {
            data.subtractScore(before - after);   //construction consumed points
        } else if (after > before && allowRefund) {
            data.addScore(after - before);        //deconstruction refunded points
        }
    }

    /** the personal pool contains points ONLY - never items from stacks or buildings. */
    static int available(PlayerData data, Item item) {
        return item == Items.dormantCyst ? Math.max(data.getScore(), 0) : 0;
    }

    // ------------------------------------------------------------------ stations

    static void buyStation(Player player, Block block, Tile tile) {
        if (block == Blocks.pulverizer) {
            Miner.buyMiner(player, tile);
        } else if (block == Blocks.mender) {
            RepairPoint.buyRepairPoint(player, tile);
        } else if (block == Blocks.phaseWall) {
            SuppressorTower.buyTower(player, tile);
        } else if (block == Blocks.radar) {
            ArmorWorkbench.buyWorkbench(player, tile);
        } else if (block == Blocks.carbideWall) {
            Laboratory.buyLab(player, tile);
        } else if (block == Blocks.beamNode) {
            Booster.buyBooster(player, tile);
        } else if (block == Blocks.slagIncinerator) {
            Recycler.buyRecycler(player, tile);
        }
    }

    // ------------------------------------------------------------------ client core

    static void updateClientCore(Player player, PlayerState st) {
        Tile tile = clientCoreTile();
        if (tile == null) return;

        //only blue players build; remove the stale core when a player switches teams
        if (player.team() != Team.blue) {
            if (st.corePlaced) {
                sendSetTile(player.con, tile, Blocks.air, Team.derelict, 0);
                st.corePlaced = false;
            }
            return;
        }

        if (!st.corePlaced || (st.refreshTimer += Time.delta) >= coreRefreshInterval) {
            st.refreshTimer = 0f;
            //client-only: a targeted SetTile creates the core on this client and
            //nowhere else. the server world stays floor-only here.
            sendSetTile(player.con, tile, coreBlock, Team.blue, 0);
            st.corePlaced = true;
            pushPoints(player, tile);
        } else if ((st.itemTimer += Time.delta) >= itemSyncInterval) {
            st.itemTimer = 0f;
            pushPoints(player, tile);
        }
    }

    /**
     * Pushes this player's points into their client-only core as the dormantCyst
     * ("Points") item. setTileItems resolves the building by POSITION on the client,
     * so no server-side building is needed. The count is absolute, which also
     * corrects the drain the client's own build prediction applies to its fake core.
     */
    static void pushPoints(Player player, Tile tile) {
        if (player.con == null || !player.con.isConnected()) return;
        PlayerData data = players.get(player.uuid());
        if (data == null) return;

        SetTileItemsCallPacket packet = new SetTileItemsCallPacket();
        packet.item = Items.dormantCyst;
        packet.amount = Math.max(data.getScore(), 0);
        packet.positions = new int[]{tile.pos()};
        player.con.send(packet, true);
    }

    static void sendSetTile(NetConnection con, Tile tile, Block block, Team team, int rotation) {
        if (con == null || !con.isConnected()) return;
        SetTileCallPacket packet = new SetTileCallPacket();
        packet.tile = tile;
        packet.block = block;
        packet.team = team;
        packet.rotation = rotation;
        con.send(packet, true);
    }
}

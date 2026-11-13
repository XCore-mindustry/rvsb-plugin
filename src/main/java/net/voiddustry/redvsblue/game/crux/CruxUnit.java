package net.voiddustry.redvsblue.game.crux;

import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Timer;
import arc.Events;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.content.StatusEffects;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.gen.BlockUnitc;
import mindustry.type.UnitType;
import mindustry.world.Tile;
import net.voiddustry.redvsblue.PlayerData;
import net.voiddustry.redvsblue.RedVsBluePlugin;
import net.voiddustry.redvsblue.util.Utils;
import net.voiddustry.redvsblue.game.crux.StageUnits;
import net.voiddustry.redvsblue.game.stats.MapStats;

import java.util.HashMap;
import java.util.HashSet;
import java.lang.Math;

public class CruxUnit {

    private static final HashMap<Unit, Long> spawnTimes = new HashMap<>();

    /**
     * uuids of crux players who left their unit on purpose (the client's "exit unit" / unitClear). They are not
     * respawned by the watchdog and may take over a crux AI unit by clicking it. Cleared when they get a unit again
     * (taking over a unit, /c, death of a taken-over unit and the normal respawn after it), leave, or a map loads.
     */
    public static final HashSet<String> exited = new HashSet<>();

    /**
     * uuid -> unit type of the crux AI unit the player took over. While set, the player respawns as that type instead
     * of their /c class. Replaced by taking over another unit; cleared by "exit unit", picking a class with /c, or a map load.
     */
    public static final HashMap<String, UnitType> possessed = new HashMap<>();
    /** uuid -> unit the player asked to take over (allowed by the control filter, confirmed in UnitChangeEvent) */
    private static final HashMap<String, Unit> pendingPossess = new HashMap<>();

    /** Called from the respawn action filter: the player pressed "exit unit". */
    public static void markExited(Player player) {
        if (player.team() == Team.crux && !player.dead()) {
            exited.add(player.uuid());
            possessed.remove(player.uuid());
        }
    }

    /** Called from the control action filter when a client's request to take over a unit was allowed. */
    public static void possessRequested(Player player, Unit unit) {
        if (player.team() == Team.crux && unit != null && !(unit instanceof BlockUnitc)) pendingPossess.put(player.uuid(), unit);
    }

    /** The unit type a crux player respawns as: the taken-over unit's type, else the /c class (dagger by default). */
    public static UnitType respawnType(Player player) {
        UnitType type = possessed.get(player.uuid());
        if (type != null && ClassChooseMenu.units.containsKey(type)) return type;
        possessed.remove(player.uuid());
        type = ClassChooseMenu.selectedUnit.get(player.uuid());
        if (type == null || !ClassChooseMenu.units.containsKey(type)) {
            type = ClassChooseMenu.units.containsKey(UnitTypes.dagger) || ClassChooseMenu.units.isEmpty()
                    ? UnitTypes.dagger : ClassChooseMenu.units.keys().next();
            ClassChooseMenu.selectedUnit.put(player.uuid(), type);
        }
        return type;
    }

    /** Forgets "exit unit" / possession state, so the next respawn is a fresh unit of the player's class. */
    public static void resetPossession(Player player) {
        exited.remove(player.uuid());
        possessed.remove(player.uuid());
        pendingPossess.remove(player.uuid());
    }

    /**
     * Turns a blue player whose unit died into a crux player with a fresh crux spawn.
     * The player is detached from whatever unit they are attached to BEFORE the team changes: Player.team() also
     * sets the team of the attached unit (and Player.update() keeps re-applying it), so changing the team first
     * recoloured that unit - e.g. a blue AI unit of the same type the client grabbed after the death.
     */
    public static void convertToCrux(Player player) {
        if (player == null || player.team() != Team.blue) return;
        PlayerData statsData = RedVsBluePlugin.players.get(player.uuid());
        Unit statsUnit = statsData != null ? statsData.getUnit() : player.unit();
        MapStats.onBlueUnitDeath(player, statsUnit);
        player.clearUnit();
        player.team(Team.crux);

        PlayerData data = RedVsBluePlugin.players.get(player.uuid());
        if (data != null) {
            data.setTeam(Team.crux);
            data.setScore(0);
            data.setKills(0);
            data.setUnit(null);
        }

        resetPossession(player);
        ClassChooseMenu.selectedUnit.remove(player.uuid());
        Utils.lockControl(player);
        callSpawn(player);
    }

    public static boolean hasExited(Player player) {
        return player.team() == Team.crux && exited.contains(player.uuid());
    }

    public static void addEvent() {
        Events.on(EventType.UnitDestroyEvent.class, event -> {
                spawnTimes.remove(event.unit);
        });

        // A blue player's unit died. Ground units fire UnitDestroyEvent right away, but a flying unit with a wreck
        // only does that when the wreck lands (and the player is detached from it as soon as it dies), so check
        // every frame instead of waiting for the 0.2 s HUD loop: until the player gets their crux spawn, the client
        // thinks it is dead and keeps asking for the closest AI unit of the same type and team.
        Events.run(EventType.Trigger.update, () -> {
            if (!RedVsBluePlugin.playing || Utils.gameover) return;
            for (Player player : Groups.player) {
                if (player.team() != Team.blue) continue;
                PlayerData data = RedVsBluePlugin.players.get(player.uuid());
                if (data == null || data.getUnit() == null) continue;
                if (data.getUnit().dead) convertToCrux(player);
            }
        });

        Events.on(EventType.UnitChangeEvent.class, event -> {
            if (event.player == null) return;
            String uuid = event.player.uuid();
            Unit requested = pendingPossess.remove(uuid);
            if (event.player.dead()) return;
            exited.remove(uuid);
            // the player took over this crux AI unit themselves: respawn as its type from now on (only if available in /c)
            if (requested != null && requested == event.unit && event.player.team() == Team.crux && ClassChooseMenu.units.containsKey(event.unit.type)) {
                possessed.put(uuid, event.unit.type);
            }
        });
        Events.on(EventType.PlayerLeave.class, event -> {
            exited.remove(event.player.uuid());
            pendingPossess.remove(event.player.uuid());
        });
        Events.on(EventType.WorldLoadEvent.class, event -> {
            exited.clear();
            possessed.clear();
            pendingPossess.clear();
        });

        // Respawn watchdog. UnitDestroyEvent only respawns ground units: a dying flying unit falls as a wreck first,
        // the player is detached from it as soon as it is dead and the destroy event comes later with isPlayer() false.
        // This also catches anything else that leaves a crux player without a unit.
        Timer.schedule(() -> {
            if (!RedVsBluePlugin.playing || Utils.gameover || RedVsBluePlugin.redSpawns.isEmpty()) return;
            for (Player player : Groups.player) {
                if (player.team() == Team.crux && player.dead() && !exited.contains(player.uuid()) && player.con != null && player.con.isConnected()) {
                    callSpawn(player);
                }
            }
        }, 1f, 0.5f);
    }
    
    public static void callSpawn(Player player) {
        exited.remove(player.uuid());

        UnitType type = respawnType(player);

        {
            Tile cruxSpawn = RedVsBluePlugin.redSpawns.random();
            // no crux spawn on this map: the watchdog does nothing either, the player just stays without a unit
            if (cruxSpawn == null) return;

            if (cruxSpawn.block() != null) {
                Call.logicExplosion(Team.crux, cruxSpawn.x*8, cruxSpawn.y*8, 80, 999999, true, true, true, true);
            }
            Unit unit = type.spawn(Team.crux, cruxSpawn);
            spawnSpecials(cruxSpawn, CruxSpawning.Context.playerRespawn);

            if (unit != null && !unit.dead) {
                unit.health = Integer.MAX_VALUE;
                unit.apply(StatusEffects.overclock, 180);;

                //unit.apply(Vars.content.statusEffect("superShielded"), 120f);
                unit.apply(Vars.content.statusEffect("shielded"), 300f);

                if (unit.type == UnitTypes.corvus) {
                    if (RedVsBluePlugin.stage == 11) {
                        unit.apply(StatusEffects.electrified, 99999f);
                        unit.apply(StatusEffects.sapped, 99999f);
                        unit.apply(StatusEffects.freezing, 99999f);
                        unit.apply(StatusEffects.fast, 99999f);
                    }
                }

                Timer.schedule(() -> {
                    if (unit.type == UnitTypes.crawler) {
                        unit.health = 20;
                        unit.addItem(Items.pyratite, 10);
                    } else if (unit.type == UnitTypes.merui) {
                        unit.health = 100;
                        unit.addItem(Items.pyratite, 99);
                    } else if (unit.type == UnitTypes.mace) {
                        unit.health = 100;
                    } else if (unit.type == UnitTypes.dagger) {
                        unit.health = 100;
                    } else {
                        unit.health = unit.type.health;
                    }

                }, 3);

                unit.spawnedByCore = true;
                Utils.assignUnit(player, unit);
            }
        }
    }

    private static void spawnCrux() {
        UnitType unitType = UnitTypes.alpha;
        if (!ClassChooseMenu.units.isEmpty()) {
            unitType = ClassChooseMenu.units.keys().toSeq().get(Utils.getRandomInt(0, ClassChooseMenu.units.size)); //Utils.getRandomInt(0, ClassChooseMenu.units.size)
        }
        spawnCrux(unitType);
    }

    private static void spawnCrux(UnitType unitType) {
        if (Utils.gameRun) {
            Tile cruxSpawn = RedVsBluePlugin.redSpawns.random();

            if (cruxSpawn != null && cruxSpawn.block() != null) {
                Call.logicExplosion(Team.crux, cruxSpawn.x*8, cruxSpawn.y*8, 80, 999999, true, true, true, true);
            }

            Unit unit = unitType.spawn(Team.crux, cruxSpawn);
            spawnTimes.put(unit, System.currentTimeMillis());
            spawnSpecials(cruxSpawn, CruxSpawning.Context.aiSpawn);

            //unit.apply(Vars.content.statusEffect("superShielded"), 120f);
            unit.apply(Vars.content.statusEffect("shielded"), 300f);

            if (unit.type == UnitTypes.corvus) {
                if (RedVsBluePlugin.stage == 11) {
                    unit.apply(StatusEffects.electrified, 99999f);
                    unit.apply(StatusEffects.sapped, 99999f);
                    unit.apply(StatusEffects.freezing, 99999f);
                    unit.apply(StatusEffects.fast, 99999f);
                }
            }

            Timer.schedule(() -> {
                if (unit.type == UnitTypes.crawler) {
                    unit.health = 20;
                    unit.addItem(Items.pyratite, 10);
                } else if (unit.type == UnitTypes.merui) {
                    unit.health = 100;
                    unit.addItem(Items.pyratite, 99);
                } else if (unit.type == UnitTypes.mace) {
                    unit.health = 100;
                } else if (unit.type == UnitTypes.dagger) {
                    unit.health = 100;
                } else {
                    unit.health = unit.type.health;
                }
            }, 2);
        }
    };


    /** True when no unit of the stage's boss type is alive (stage 11: any team, like before; other stages: crux only). */
    private static boolean bossDead(int bossStage) {
        UnitType boss = StageUnits.bosses.get(bossStage);
        if (boss == null) return true;
        for (Unit u : Groups.unit) {
            if (u.type == boss && (bossStage == 11 || u.team == Team.crux)) return false;
        }
        return true;
    }

    private static void spawnSpecials(Tile tile, CruxSpawning.Context context) {
        if (tile == null) return;
        for (CruxSpawning.SpecialSpawn special : CruxSpawning.specials(RedVsBluePlugin.stage, context)) {
            if (!Mathf.chance(special.chance)) continue;
            UnitType type = Vars.content.unit(special.type);
            if (type == null) continue;
            int count = Mathf.random(special.min, special.max);
            for (int i = 0; i < count; i++) type.spawn(Team.crux, tile);
        }
    }

    public static void checkUnitCount() {
        if (Utils.gameRun) {
            int players = Groups.player.size();
            int stage = RedVsBluePlugin.stage;

            Seq<UnitType> types = ClassChooseMenu.units.keys().toSeq();
            int[] quotas = CruxSpawning.quotas(stage, types, players);
            if (types.isEmpty()) return;

            final HashMap<UnitType, Integer> cruxUnits = new HashMap<>();
            long now = System.currentTimeMillis();

            for (int i = 0; i < types.size; i++) {
                UnitType type = types.get(i);
                int quota = quotas[i];
                if (quota <= 0) continue;

                Groups.unit.each(u -> {
                    if (u.team == Team.crux && u.type == type) {
                        Long spawnedAt = spawnTimes.get(u);
                        if (spawnedAt != null && now - spawnedAt < CruxSpawning.recentWindowMillis()) {
                            cruxUnits.merge(type, 1, Integer::sum);
                        }
                    }
                });

                if (cruxUnits.getOrDefault(type, 0) < quota) {
                    spawnCrux(type);
                }
            }
        }
    }
}

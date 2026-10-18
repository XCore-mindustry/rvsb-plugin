package net.voiddustry.redvsblue.game.crux;

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
import net.voiddustry.redvsblue.RedVsBluePlugin;
import net.voiddustry.redvsblue.util.Utils;
import net.voiddustry.redvsblue.game.crux.StageUnits;

import java.util.Random;
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
        if (type != null) return type;
        type = ClassChooseMenu.selectedUnit.get(player.uuid());
        if (type == null) {
            type = UnitTypes.dagger;
            ClassChooseMenu.selectedUnit.put(player.uuid(), type);
        }
        return type;
    }

    public static boolean hasExited(Player player) {
        return player.team() == Team.crux && exited.contains(player.uuid());
    }

    public static void addEvent() {
        Events.on(EventType.UnitDestroyEvent.class, event -> {
                spawnTimes.remove(event.unit);
        });

        Events.on(EventType.UnitChangeEvent.class, event -> {
            if (event.player == null) return;
            String uuid = event.player.uuid();
            Unit requested = pendingPossess.remove(uuid);
            if (event.player.dead()) return;
            exited.remove(uuid);
            // the player took over this crux AI unit themselves: respawn as its type from now on
            if (requested != null && requested == event.unit && event.player.team() == Team.crux) possessed.put(uuid, event.unit.type);
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

            if (cruxSpawn != null && cruxSpawn.block() != null) {
                Call.logicExplosion(Team.crux, cruxSpawn.x*8, cruxSpawn.y*8, 80, 999999, true, true, true, true);
            }
            Unit unit = type.spawn(Team.crux, cruxSpawn);

            if (RedVsBluePlugin.stage >= 11) {
                int renaleAmount = 2 + new Random().nextInt(8);
                for (int i = 0; i < renaleAmount; i++) {
                    UnitTypes.renale.spawn(Team.crux, cruxSpawn);
                }
            }

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
                        unit.apply(StatusEffects.burning, 99999f);
                        unit.apply(StatusEffects.fast, 99999f);
                        unit.apply(StatusEffects.corroded, 99999f);
                        unit.apply(StatusEffects.tarred, 99999f);
                        unit.apply(StatusEffects.sporeSlowed, 99999f);
                        unit.apply(StatusEffects.shocked, 99999f);
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

            if (RedVsBluePlugin.stage == 11 && bossDead(11)) {
                Random rand = new Random();
                if (rand.nextInt(20) == 1) {
                    UnitTypes.latum.spawn(Team.crux, cruxSpawn);
                }
                if (rand.nextInt(13) == 1) {
                    UnitTypes.conquer.spawn(Team.crux, cruxSpawn);
                }
                if (rand.nextInt(7) == 1) {
                    UnitTypes.tecta.spawn(Team.crux, cruxSpawn);
                }
            }

            // stage 13 after the Voidweaver died: same odds as stage 11, with T6 / custom units
            if (RedVsBluePlugin.stage == 13 && bossDead(13)) {
                Random rand = new Random();
                if (rand.nextInt(20) == 1) {
                    spawnNamed("dp-aranethys-crux-t6", cruxSpawn);
                }
                if (rand.nextInt(13) == 1) {
                    spawnNamed("dp-venomreach-crux-t6", cruxSpawn);
                }
                if (rand.nextInt(7) == 1) {
                    spawnNamed("dp-devastator-unit", cruxSpawn);
                }
            }

            Unit unit = unitType.spawn(Team.crux, cruxSpawn);
            spawnTimes.put(unit, System.currentTimeMillis());

            if (RedVsBluePlugin.stage >= 11) {
                int renaleAmount = 2 + new Random().nextInt(8);
                for (int i = 0; i < renaleAmount; i++) {
                    UnitTypes.renale.spawn(Team.crux, cruxSpawn);
                }
            }

            //unit.apply(Vars.content.statusEffect("superShielded"), 120f);
            unit.apply(Vars.content.statusEffect("shielded"), 300f);

            if (unit.type == UnitTypes.corvus) {
                if (RedVsBluePlugin.stage == 11) {
                    unit.apply(StatusEffects.electrified, 99999f);
                    unit.apply(StatusEffects.sapped, 99999f);
                    unit.apply(StatusEffects.freezing, 99999f);
                    unit.apply(StatusEffects.burning, 99999f);
                    unit.apply(StatusEffects.fast, 99999f);
                    unit.apply(StatusEffects.corroded, 99999f);
                    unit.apply(StatusEffects.tarred, 99999f);
                    unit.apply(StatusEffects.sporeSlowed, 99999f);
                    unit.apply(StatusEffects.shocked, 99999f);
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

    private static void spawnNamed(String name, Tile tile) {
        UnitType type = Vars.content.unit(name);
        if (type != null) type.spawn(Team.crux, tile);
    }

    public static void checkUnitCount() {
        if (Utils.gameRun) {
            int players = Groups.player.size();
            int stage = RedVsBluePlugin.stage;

            // stage 13 spawns twice as many as stages 11 and 12
            int cruxUnitsCount = Math.round((players + Math.min(stage, 12)) / (float) 3) * (stage >= 13 ? 2 : 1);

            final HashMap<UnitType, Integer> cruxUnits = new HashMap<>();

            if (!ClassChooseMenu.units.isEmpty()) {
                ClassChooseMenu.units.keys().toSeq().each(type -> {
                    Groups.unit.each(u -> {
                        if (u.team == Team.crux && u.type == type) {
                        
                            if ((!(spawnTimes.get(u) == null)) && System.currentTimeMillis()-spawnTimes.get(u)<45000) {
                                if (cruxUnits.get(type) == null) {
                                    cruxUnits.put(type, 0);
                                } else {
                                    cruxUnits.put(type, cruxUnits.get(type)+1);
                                }
                            }
                        }
                    });
                    if (cruxUnits.get(type) == null || cruxUnits.get(type) < cruxUnitsCount/ClassChooseMenu.units.keys().toSeq().size) {
                        spawnCrux(type);
                    }
                });
            }
        }
    }
}

package net.voiddustry.redvsblue.game.building;

import java.lang.Math;
import java.lang.Runnable;
import arc.util.Timer;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import arc.*;
import arc.util.*;
import arc.struct.Seq;
import arc.math.geom.QuadTree.*;
import mindustry.*;
import mindustry.world.Tile;
import mindustry.world.blocks.payloads.*;
import mindustry.world.blocks.*;
import mindustry.world.Block;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.Vars;
import mindustry.game.Rules;
import mindustry.content.*;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.gen.Call;
import mindustry.game.Team;
import mindustry.mod.*;
import mindustry.net.Administration.*;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.type.Category;

import net.voiddustry.redvsblue.RedVsBluePlugin;
import net.voiddustry.redvsblue.game.stations.*;


// updates construct blocks instead of the game doing it, original in usurvplugin
public class UpdateConstructBlocks implements Runnable {
  // false from map load until the blue core is killed and player data is reset
  public static volatile boolean open = false;

  /** Owner + tile of a deconstruction in progress; survives a paused deconstruction. */
  private record BreakKey(String uuid, int x, int y) {}

  private static final Map<BreakKey, Block> breakSessions = new ConcurrentHashMap<>();

  public static void start() {
    StationBreaking.load();
    Timer.schedule(new UpdateConstructBlocks(), 0f, 0.2f);
    // a rejected plan is dropped by NetServer.clientSnapshot, which also removes it from the client's queue
    Vars.netServer.admins.addActionFilter(a -> a.player == null || (a.type == ActionType.placeBlock ? !open || legal(a.player, a.block, a.tile) : a.type != ActionType.breakBlock || canDeconstruct(a.player, a.tile)));
    // drop station data the moment the block is gone, so the owner can rebuild right away
    Events.on(BlockBuildEndEvent.class, e -> {
      if (!e.breaking || e.tile == null) return;
      Tile tile = e.tile;
      Miner.removeAt(tile);
      RepairPoint.removeAt(tile);
      SuppressorTower.removeAt(tile);
      Booster.removeAt(tile);
      Recycler.removeAt(tile);
      ArmorWorkbench.removeAt(tile);
      Laboratory.removeAt(tile);
    });
    Events.on(WorldLoadEvent.class, e -> {
      breakSessions.clear();
      StationBreaking.load();
    });
  }

  /** Station block on this tile, including an in-progress deconstruction, or null. */
  static Block stationBlock(Tile tile) {
    return StationUtils.stationBlock(tile);
  }

  static boolean isStationBlock(Block block) {
    return StationUtils.isStationBlock(block);
  }

  static boolean ownsStation(Block block, String uuid, Tile tile) {
    if (block == Blocks.pulverizer) return Miner.ownsAt(uuid, tile);
    if (block == Blocks.mender) return RepairPoint.ownsAt(uuid, tile);
    if (block == Blocks.phaseWall) return SuppressorTower.ownsAt(uuid, tile);
    if (block == Blocks.beamNode) return Booster.ownsAt(uuid, tile);
    if (block == Blocks.slagIncinerator) return Recycler.ownsAt(uuid, tile);
    if (block == Vars.content.block("dp-workbench-station")) return ArmorWorkbench.ownsAt(uuid, tile);
    if (block == Vars.content.block("dp-laboratory-station")) return Laboratory.ownsAt(uuid, tile);
    return false;
  }

  /** Vanilla deconstruct is allowed only on a station owned by this player at this exact tile. */
  static boolean canDeconstruct(Player player, Tile tile) {
    if (player == null || player.team() != Team.blue || tile == null) return false;
    Block station = stationBlock(tile);
    if (station == null) return false;
    if (tile.build instanceof ConstructBuild) {
      return breakSessions.get(new BreakKey(player.uuid(), tile.x, tile.y)) == station;
    }
    return ownsStation(station, player.uuid(), tile);
  }

  /**
   * Drives deconstruction of the player's own stations. Units have buildSpeed 0.01, so the vanilla
   * construction tick would never move; we step the vanilla ConstructBuild instead, which keeps the
   * progress bar, effects and tile sync intact. Progress only advances while the plan is kept and
   * the unit stays in range.
   */
  void updateBreaks() {
    if (!RedVsBluePlugin.playing) return;
    for (Unit u : Groups.unit) {
      if (u.team != Team.blue || u.dead || u.plans().isEmpty()) continue;
      Player player = u.getPlayer();
      if (player == null || player.dead()) continue;

      var plans = u.plans();
      for (int i = plans.size - 1; i >= 0; i--) {
        BuildPlan plan = plans.get(i);
        if (!plan.breaking) continue;

        Tile tile = Vars.world.tile(plan.x, plan.y);
        if (tile == null || tile.block() == Blocks.air) {
          plans.removeIndex(i);
          continue;
        }
        if (!canDeconstruct(player, tile)) {
          plans.removeIndex(i);
          continue;
        }

        Block station = stationBlock(tile);
        if (station == null) {
          plans.removeIndex(i);
          continue;
        }
        if (u.dst(tile.x * 8f, tile.y * 8f) > u.type.buildRange) continue;

        BreakKey key = new BreakKey(player.uuid(), tile.x, tile.y);
        if (!(tile.build instanceof ConstructBuild cb)) {
          breakSessions.put(key, station);
          // start the vanilla deconstruction; this syncs the construction frame to clients
          Call.beginBreak(u, Team.blue, plan.x, plan.y);
          continue;
        }
        if (cb.current != station) {
          plans.removeIndex(i);
          continue;
        }

        breakSessions.put(key, station);
        boolean owned = ownsStation(station, player.uuid(), tile);
        cb.deconstruct(u, null, StationBreaking.step(station));
        if (tile.block() == Blocks.air) {
          // only a completed deconstruction by the owner refunds points; deaths/map changes/explosions do not
          if (owned) refund(player, station);
          Log.info("[rvsb-break] @ finished breaking @ at @,@", player.name, station, tile.x, tile.y);
          plans.removeIndex(i);
        }
      }
    }

    breakSessions.entrySet().removeIf(e -> {
      BreakKey key = e.getKey();
      Tile tile = Vars.world.tile(key.x(), key.y());
      if (tile == null || !(tile.build instanceof ConstructBuild cb) || cb.current != e.getValue()) return true;

      Player owner = Groups.player.find(p -> p.uuid().equals(key.uuid()));
      if (owner == null || owner.dead() || owner.unit() == null || owner.team() != Team.blue) {
        // the owner died or left: do not leave a half-deconstructed wreck behind
        Call.deconstructFinish(tile, cb.current, null);
        return true;
      }
      return false;
    });
  }

  static int cost(Block block) {
    for (ItemStack stack : block.requirements) if (stack.item == Items.dormantCyst) return stack.amount;
    return 0;
  }

  /** Half of the station price, paid only after the owner fully deconstructs it. */
  static void refund(Player player, Block station) {
    if (player == null || station == null || player.team() != Team.blue) return;
    int amount = cost(station) / 2;
    if (amount <= 0) return;
    var data = RedVsBluePlugin.players.get(player.uuid());
    if (data != null) data.addScore(amount);
  }

  static boolean legal(Player player, Block block, Tile tile) {
    var data = RedVsBluePlugin.players.get(player.uuid());
    int cost = cost(block);
    if (player.team() != Team.blue || data == null || cost == 0 || data.getScore() < cost || Vars.state.rules.isBanned(block)) return false;

    if (isStationBlock(block)) {
      // stations still must go on a free tile and obey the one-per-player limit
      if (!StationUtils.isFreePlacement(block, tile)) return false;
      String id = player.uuid();
      boolean owned;
      if (block == Blocks.pulverizer) owned = Miner.owns(id);
      else if (block == Blocks.mender) owned = RepairPoint.owns(id);
      else if (block == Blocks.phaseWall) owned = SuppressorTower.owns(id);
      else if (block == Blocks.beamNode) owned = Booster.owns(id);
      else if (block == Blocks.slagIncinerator) owned = Recycler.owns(id);
      else if (block == Vars.content.block("dp-workbench-station")) owned = ArmorWorkbench.owns(id);
      else if (block == Vars.content.block("dp-laboratory-station")) owned = Laboratory.owns(id);
      else owned = false;
      return !owned;
    }

    // ordinary blocks keep their normal placement rules; only stations are protected from being covered
    return !StationUtils.footprintContainsStation(block, tile);
  }

  @Override
  public void run() {
    try {
      updateBreaks();
      if (!open) return;
      
      for (Unit u : Groups.unit) {
        if (u.team == Team.blue && u.buildPlan() != null) {
          if (u.buildPlan().worldContext) { 
            if (u.isBuilding()) {
              BuildPlan bp = u.buildPlan();
              // breaking plans carry no block and deconstruct instantly on their own
              if (bp.breaking) continue;
              
              int cost = cost(bp.block);
              Tile tile = Vars.world.tile(bp.x,bp.y);

              boolean blocked = isStationBlock(bp.block)
                      ? !StationUtils.isFreePlacement(bp.block, tile)
                      : StationUtils.footprintContainsStation(bp.block, tile);
              if (tile == null || blocked) {
                u.plans().removeFirst();
                continue;
              }
                           
              if (u.getPlayer() != null && u.buildPlan().placeable(Team.blue) && u.dst(bp.x*8,bp.y*8)<Vars.buildingRange) {
                Player player = u.getPlayer();
                if (u.buildPlan().breaking == false && u.isBuilding()) {
                  //Log.info("Attempting to consume " + cost + " ,from player " + player);
                  if (RedVsBluePlugin.players.get(player.uuid()).getScore()>=cost && cost!=0) {
                    //Log.info("finishing construction");
                    if (bp.block.category == Category.logic) {
                      if (bp.block == Blocks.pulverizer) {
                        Miner.buyMiner(player, tile);
                      } else if (bp.block == Blocks.mender) {
                        RepairPoint.buyRepairPoint(player, tile);
                      } else if (bp.block == Blocks.phaseWall) {
                        SuppressorTower.buyTower(player, tile);
                      } else if (bp.block == Vars.content.block("dp-workbench-station")) {
                        ArmorWorkbench.buyWorkbench(player, tile);
                      } else if (bp.block == Vars.content.block("dp-laboratory-station")) {
                        Laboratory.buyLab(player, tile);
                      } else if (bp.block == Blocks.beamNode) {
                        Booster.buyBooster(player, tile);
                      } else if (bp.block == Blocks.slagIncinerator) {
                        Recycler.buyRecycler(player, tile);
                      }
                    } else {
                    RedVsBluePlugin.players.get(player.uuid()).subtractScore(cost);
                    Call.constructFinish(tile,bp.block,null,(byte)bp.rotation,player.team(),bp.config);
                    tile.build.placed();
                    }
                  } else {
                    if (tile.block() instanceof ConstructBlock cb) {
                      tile.setNet(Blocks.air,Team.derelict,0);
                    }
                  }
                }
              }
            }
          }
        }
      }
    } catch (Exception e) {
      Log.info("goofy ahh exception in the building system");
      e.printStackTrace();
    }
  }
}

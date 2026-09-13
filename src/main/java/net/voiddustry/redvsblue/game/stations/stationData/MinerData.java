package net.voiddustry.redvsblue.game.stations.stationData;

import mindustry.gen.Player;
import mindustry.gen.WorldLabel;
import mindustry.world.Tile;

public class MinerData {
    private Player owner;
    private Tile tileOn;
    private int exp;
    private int maxExp;
    private int lvl;
    private WorldLabel label;

    public MinerData(Player owner, Tile tileOn, Integer exp, Integer maxExp, Integer lvl, WorldLabel label) {
        this.owner = owner;
        this.tileOn = tileOn;
        this.exp = exp;
        this.maxExp = maxExp;
        this.lvl = lvl;
        this.label = label;
    }

    public MinerData(Player owner, Tile tileOn, Integer exp, Integer maxExp, Integer lvl) {
        this(owner, tileOn, exp, maxExp, lvl, null);
    }

    public MinerData(Player owner, Tile tileOn, WorldLabel label) {
        this(owner, tileOn, 0, 15, 1, label);
    }

    public MinerData(Player owner, Tile tileOn) {
        this(owner, tileOn, 0, 15, 1, null);
    }

    public Player getOwner() {
        return owner;
    }

    public Tile getTileOn() {
        return tileOn;
    }

    public int getExp() {
        return exp;
    }

    public void setExp(int amount) {
        this.exp = amount;
    }

    public void addExp(int amount) {
        this.exp += amount;
    }

    public int getMaxExp() {
        return maxExp;
    }

    public void setMaxExp(int amount) {
        this.maxExp = amount;
    }

    public void setLvl(int amount) {
        this.lvl = amount;
    }

    public void addLvl() {
        this.lvl++;
    }

    public int getLvl() {
        return lvl;
    }

    public WorldLabel getLabel() {
        return label;
    }

    public void setLabel(WorldLabel label) {
        this.label = label;
    }

    public void updateLabel() {
        if (label != null) {
            label.text = owner.name + "[gold]'s Miner\n[gray][ [gold]" + lvl + "[] | [accent]" + exp + " / " + maxExp + "[gray] ]";
        }
    }

    public void destroy() {
        if (label != null) {
            label.hide();
        }
    }
}

package net.voiddustry.redvsblue.evolution;

/** One unit in the evolution tree. Loaded from config/rvsbUnits/evolutions.hjson by {@link Evolutions#load()}. */
public final class Evolution {

    public final String unitName;

    public final int tier;
    /** base price, already multiplied by the global price multiplier */
    public final int cost;
    /** stage at which the base price applies; see Laboratory#getMultiplier */
    public final int stage;
    /** units this one can evolve into */
    public final String[] evolutions;

    public Evolution(
            String unitName,
            int tier,
            int cost,
            String[] evolutions,
            int stage
    ) {
        this.unitName = unitName;
        this.tier = tier;
        this.cost = cost;
        this.evolutions = evolutions;
        this.stage = stage;
    }
}

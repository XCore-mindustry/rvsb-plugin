package net.voiddustry.redvsblue.evolution;

import static net.voiddustry.redvsblue.util.UnitsConfig.multp;

public enum Evolution {

    DAGGER("dagger", 1, 2 * multp,
            new String[]{"atrax", "mace", "merui", "flare", "nova", "mono"}, 1),
    NOVA("nova", 1, 3 * multp,
            new String[]{"pulsar", "merui", "flare", "dagger", "mono"}, 1),
    MERUI("merui", 1, 8 * multp,
            new String[]{"atrax", "nova", "flare", "dagger", "mono"}, 1),
    FLARE("flare", 1, 6 * multp,
            new String[]{"risso", "nova", "merui", "dagger", "mono"}, 1),
    MONO("mono", 1, 5 * multp,
            new String[]{"poly", "nova", "merui", "dagger"}, 1),

    PULSAR("pulsar", 2, 25 * multp,
            new String[]{"quasar"}, 2),
    ATRAX("atrax", 2, 18 * multp,
            new String[]{"cleroi"}, 2),
    RISSO("risso", 2, 45 * multp,
            new String[]{"zenith"}, 3),
    POLY("poly", 2, 21 * multp,
            new String[]{"retusa"}, 2),

    MACE("mace", 3, 20 * multp,
            new String[]{"fortress"}, 2),
    QUASAR("quasar", 3, 60 * multp,
            new String[]{"dp-vela-blue-unit"}, 3),
    CLEROI("cleroi", 3, 65 * multp,
            new String[]{"spiroct"}, 3),
    ZENITH("zenith", 3, 70 * multp,
            new String[]{"minke"}, 3),
    RETUSA("retusa", 3, 15 * multp,
            new String[]{"oxynoe"}, 3),

    FORTRESS("fortress", 4, 48 * multp,
            new String[]{"locus"}, 2),
    VELA("dp-vela-blue-unit", 4, 170 * multp,
            new String[]{"corvus"}, 6),
    SPIROCT("spiroct", 4, 95 * multp,
            new String[]{"dp-aractid-unit", "tecta"}, 4),
    MINKE("minke", 4, 90 * multp,
            new String[]{"avert"}, 3),
    OXYNOE("oxynoe", 4, 35 * multp,
            new String[]{"mega"}, 3),

    LOCUS("locus", 5, 55 * multp,
            new String[]{"precept"}, 4),
    CORVUS("corvus", 5, 300 * multp,
            new String[]{"aegires"}, 7),
    MEGA("mega", 5, 100 * multp,
            new String[]{"cyerce"}, 4),

    PRECEPT("precept", 6, 60 * multp,
            new String[]{"scepter", "dp-stryker-unit"}, 5),
    AEGIRES("aegires", 6, 450 * multp,
            new String[]{"navanax"}, 8),
    AVERT("avert", 6, 27 * multp,
            new String[]{"bryde", "obviate"}, 4),
    CYERCE("cyerce", 6, 150 * multp,
            new String[]{"quad", "dp-giga-unit"}, 5),
    TECTA("tecta", 6, 520 * multp,
            new String[]{"collaris"}, 7),
    ARACTID("dp-aractid-unit", 6, 185 * multp,
            new String[]{"toxopid"}, 5),

    SCEPTER("scepter", 7, 105 * multp,
            new String[]{"vanquish"}, 6),
    NAVANAX("navanax", 7, 450 * multp,
            new String[]{"flare", "merui", "dagger"}, 9),
    TOXOPID("toxopid", 7, 415 * multp,
            new String[]{"nova", "flare", "dagger", "mono"}, 7),
    BRYDE("bryde", 7, 180 * multp,
            new String[]{"antumbra"}, 5),
    OBVIATE("obviate", 7, 90 * multp,
            new String[]{"quell", "dp-spearhead-unit"}, 5),
    QUAD("quad", 7, 450 * multp,
            new String[]{"oct"}, 7),
    GIGA("dp-giga-unit", 7, 360 * multp,
            new String[]{"dp-omni-unit"}, 7),

    VANQUISH("vanquish", 8, 200 * multp,
            new String[]{"reign"}, 6),
    STRYKER("dp-stryker-unit", 8, 320 * multp,
            new String[]{"dp-devastator-unit"}, 6),
    COLLARIS("collaris", 8, 1170 * multp,
            new String[]{"nova", "flare", "dagger", "mono"}, 7),
    ANTUMBRA("antumbra", 8, 180 * multp,
            new String[]{"eclipse", "sei"}, 6),
    OCT("oct", 8, 1300 * multp,
            new String[]{"merui", "nova", "flare", "dagger"}, 8),
    OMNI("dp-omni-unit", 8, 980 * multp,
            new String[]{"flare", "merui", "mono", "nova"}, 8),
    QUELL("quell", 8, 180 * multp,
            new String[]{"disrupt"}, 6),
    SPEARHEAD("dp-spearhead-unit", 8, 140 * multp,
            new String[]{"disrupt"}, 6),

    REIGN("reign", 9, 450 * multp,
            new String[]{"conquer"}, 7),
    DEVASTATOR("dp-devastator-unit", 9, 580 * multp,
            new String[]{"nova", "merui", "flare"}, 8),
    ECLIPSE("eclipse", 9, 270 * multp,
            new String[]{"dp-daybreak-unit"}, 6),
    DISRUPT("disrupt", 9, 800 * multp,
            new String[]{"nova", "flare", "dagger"}, 8),

    CONQUER("conquer", 10, 450 * multp,
            new String[]{"merui", "nova", "flare", "mono"}, 7),
    SEI("sei", 10, 350 * multp,
            new String[]{"omura"}, 6),

    OMURA("omura", 11, 1650 * multp,
            new String[]{"merui", "nova", "dagger", "mono"}, 9),
    DAYBREAK("dp-daybreak-unit", 8, 960 * multp,
            new String[]{"merui", "nova", "flare", "dagger"}, 9);

    public final String unitName;

    public final int tier;
    public final int cost;
    public final int stage;
    public final String[] evolutions;

    Evolution(
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
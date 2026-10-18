
## semi ai generated balancer docs

- Files:
  - `rvsbUnits/spawns.hjson`: global settings and spawners, plus map exceptions.
  - `rvsbUnits/mapspawns/<map>.hjson`: one file per map.
  - `rvsbUnits/unitWeights.hjson`: unit weights.
- `spawns.example.hjson` and `mapspawn.example.hjson` show every field.
- Strength values are recomputed every `updateSeconds` (default 60). Spawn checks run every second.
- console commands `crux-spawns` - info; `crux-spawns reload` reload configs.

## unitWeights.hjson
```hjson
default: 1
weights: {
  sei: 2.2
  vela: 0.4
}
```
Used in value calculations with weighted: true

## spawns.hjson
| key | meaning |
|---|---|
| `updateSeconds` | how often strength values refresh (default 60) |
| `stageSuppression` | `{stage: fraction}`, removes that fraction of spawns in a stage by dividing every cooldown by 1 − fraction (e.g. `{13: 0.55}` → cooldowns ×2.22). Applies to all spawners, including map files |
| `global.spawners` | spawners for every map… |
| `global.exceptions` | …except these maps |

## mapspawns/&lt;map&gt;.hjson
| key | meaning |
|---|---|
| `spawners` | extra spawners for this map |
| `globalMultiplier` | multiplies the strength of the *global* spawners on this map (0 = they never fire) |

maps are matched by filename.

### Spawner
| key | default | meaning |
|---|---|---|
| `name` | | shown in logs / `crux-spawns` |
| `metric` | `sum` | one of the four below |
| `hp` | `max` | `max` = maxHealth, `currentPlus30` = health + 30 % of maxHealth |
| `weighted` | `true` | apply `unitWeights.hjson` |
| `peak` | false | the value never goes down: it keeps the highest reading since the map started |
| `decay` | 0.75 | factor for `decaying` |
| `multiplier` | 1 | strength multiplier |
| `flex` | false | value moves linearly toward the target and would reach it in `flexSeconds` (300). It re-aims at each refresh and starts at 0 |
| `playerScaling` | 0 | s: cooldown × (1 − s)^((bluePlayers − 1) / 9). So 0.5 halves the cooldown at 10 blue players and keeps shrinking it above 10. Negative values slow spawns down |
| `minStage` / `maxStage` | – | only active in these game stages (1–13) |
| `graceSeconds` | 0 | no spawns in the first N seconds of the round |
| `playersOnly` | false | count only player-controlled blue units |
| `minUnitHp` / `maxUnitHp` | – | count only units whose maxHealth is in range (raw, unweighted) |
| `include` / `exclude` | – | unit type lists |
| `buildingBonus` | – | `{"block": value}`, adds value × number of blue buildings of that block |
| `waitForDeath` | false | cooldown starts only after everything from the last spawn is dead |
| `tiers` | | see below |

### Metrics
Here *h* is the weighted HP of each counted blue unit.

| metric | value |
|---|---|
| `sum` | Σ h |
| `average` | Σ h / count |
| `avgTopHalf` | average + max h / 2 |
| `decaying` | h₁ + h₂·d + h₃·d² + …, sorted from highest |

### Tier
The highest tier with `min ≤ value < max` (and a The file name is matched against the map's file name without extension, e.g. `mapspawns/space_ship (rerun castle).hjson`. If nothing matches, it is matched against the map's display name. Case is ignored. The file is read on every map load, so edits apply from the next map.matching stage) fires.
- After it fires, the spawner waits that tier's `cooldown` (scaled by `playerScaling`).
- Below the lowest tier, nothing happens.

| key | meaning |
|---|---|
| `min` / `max` | value range (`max` is optional = no upper limit) |
| `cooldown` | seconds until this spawner can fire again |
| `minStage` / `maxStage` | optional stage gate for this tier |
| `options` | `[{"weight": w, "units": [...]}]`. One option is picked by weight. `"units": []` = nothing this time |
| `units` | shorthand for a single option |

### Unit entry
| key | meaning |
|---|---|
| `type` | unit name (datapatch units like `dp-regent-unit` work if loaded; unknown names are skipped with one warning) |
| `wave` | instead of `type`: spawns the map's wave N groups (like `spawnwave`: groups bound to another spawn point are skipped) |
| `count` | how many (default 1) |
| `extraPerBlueUnit` | adds ⌊random(k × blue unit count)⌋ more |
| `effects` | `["boss", {"name": "overclock", "seconds": 10}]`. Default duration is permanent (or `effectSeconds`) |
| `shield`, `health`, `flag` | set on the spawned unit |

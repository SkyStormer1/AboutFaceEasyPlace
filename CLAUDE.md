# About Face Easy Place — working notes

Client-side Fabric mod for Minecraft 26.2, a plugin for Litematica. On a server without Carpet or
Servux it makes Easy Place put blocks down in the orientation (and post-placement state) the
schematic asks for. `README.md` says what it does for players; this file is for whoever works on it.

## Status

Tested in game against a vanilla 26.2 server with Litematica 0.28.8 (old `WorldUtils` path, the
default). Confirmed by the placement audit: stairs, logs, pillars, chains, hoppers, furnaces, chests,
anvils, looms, stonecutters, trapdoors, fence gates, doors, repeaters, comparators, dust, pistons,
sticky pistons, observers, dispensers, droppers, crafters, barrels, signs, hanging signs, banners,
skulls, lanterns, end rods, lightning rods, rails. Slabs are untested.

## How it works

A vanilla server decides orientation from the player's rotation, the clicked face and point, and
whether they are sneaking. The mod searches for values of those that make vanilla's own
`getPlacementState` produce the wanted state, claims them for one placement, and restores the truth.

- **Body yaw vs head yaw.** Most blocks read the body (`getYRot`), which the server takes straight
  from a rotation packet. Pistons, observers, dispensers, droppers, crafters and barrels read the
  head (`getViewYRot` → `yHeadRot`), which the server only copies from the body once per tick in
  `Player.aiStep`. So the search runs body-only first, then with the head turned. A head answer is
  delivered by `Anticipation` (claims ahead of time for the block under the crosshair) or, as a
  fallback, by `RotationHold` (keeps the claim across two ticks, then places).
- **Trap:** the client's `LocalPlayer.getViewYRot` returns the *body* yaw. Simulations must go
  through `Placing.headOverride` (`LocalPlayerViewMixin`) or they will lie about head-reading blocks.
- **Post-placement state** (`Adjustments`): repeater delay, comparator mode, dust dot/cross, note,
  daylight detector, lever, open doors/trapdoors/gates — set by using the block straight after
  placing, main hand, not sneaking, while the placement claim is still in force.
- **Clicks** are only ever ones the server accepts (`Aligner.usable`): within 1 block of the clicked
  block's centre, in reach, and never on a block that does something when used (`Interaction`).

## Files

| File | What it is |
|:--|:--|
| `Aligner.kt` | The search. Read this first. |
| `EasyPlaceHook.kt` | Placement, claims, post-placement uses, vanilla-click handling. |
| `RotationHold.kt`, `Anticipation.kt` | Claims that must stand across a server tick. |
| `Adjustments.kt` | Settings that only exist after placement. |
| `Interaction.kt` | Which blocks must never be clicked to place against. |
| `WallMounts.kt` + `LitematicaEasyPlaceUtilsMixin` | Lifts Litematica's support-block rule for blocks that stand on their own. |
| `Litematica.kt` | Every reflective read of Litematica. |
| `Engagement.kt` | Whether the mod acts at all (vanilla server, Litematica protocol slabs-only/none). |
| `PlacementAudit.kt` | With verbose logging, checks what the server really placed. |
| `Tweakeroo.kt` | One-time warning when Tweakeroo's Accurate Block Placement is on. |
| `mixin/` | `useItemOn` hook, packet rewrite, head-yaw override, accessors. |

## Litematica facts

- Minecraft 26.x Litematica is [sakura-ryoko/litematica](https://github.com/sakura-ryoko/litematica),
  branch `LTS/26.2`, Mojang mappings.
- Two Easy Place paths: `WorldUtils` (old, default because `easyPlacePostRewrite` is false) and
  `EasyPlaceUtils`. The hook recognises both by stack walk; `Litematica.whileHandling` covers the new
  one for placements made outside its call.
- For torches, banners, signs and skulls Litematica moves the click to the support block but keeps the
  hit position in the target, which vanilla rejects as out of range, and refuses outright when the
  support is missing. `WallMounts` switches that off for blocks that can survive without support.
- When Litematica's ray misses a small schematic block it hands the click to vanilla (and to
  Tweakeroo). `EasyPlaceHook.interceptVanillaClick` catches those.

## Conventions

- No compile-time dependency on Litematica: reflection, or mixins targeted by name with `require = 0`.
- Never place a block wrongly. Where the wanted state cannot be reached, decline and say so.
- Comments explain why, not what.
- Test against a real vanilla server; single player is ignored by design.

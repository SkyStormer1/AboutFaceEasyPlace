<div align="center">

# About Face Easy Place

### *A Litematica Plugin*

**Litematica's Easy Place, with the blocks facing the right way on a vanilla server.**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-62B47A?style=for-the-badge&logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3+-DBD0B4?style=for-the-badge&logo=fabric&logoColor=333)](https://fabricmc.net/)
[![Litematica](https://img.shields.io/badge/Requires-Litematica-9B59B6?style=for-the-badge)](https://github.com/sakura-ryoko/litematica)
[![Client only](https://img.shields.io/badge/Client--side-No%20server%20mod-4A90D9?style=for-the-badge)](#installing)
[![Licence](https://img.shields.io/badge/Licence-MIT-555?style=for-the-badge)](LICENSE)

</div>

---

> Build a staircase from a schematic on any normal server and every stair comes out facing whichever
> way you happened to be standing. Hoppers feed the wrong direction. Logs lie along the wrong axis.
> Every furnace stares back at you.
>
> **This fixes that.** Nothing is needed server-side.

<div align="center">

| | Stairs | Hoppers | Logs | Furnaces | Observers |
|:--|:--:|:--:|:--:|:--:|:--:|
| **Easy Place alone** | ❌ | ❌ | ❌ | ❌ | ❌ |
| **With this** | ✅ | ✅ | ✅ | ✅ | ✅ |

</div>

> [!NOTE]
> **Not yet tested in-game.** The code compiles and its placement search is covered by [34 automated
> checks](verification/), but it has not yet been run against a live server. Treat the first build as
> a first build. *(Delete this note once it has been round the block.)*

---

## Why Easy Place gets it wrong

Easy Place has to tell the server not just *where* a block goes but **which way round**, and the
server it is talking to has no idea schematics exist.

Litematica's answer is to encode the wanted block state into the fractional part of the click
position, and have a server-side mod decode it — `accurateBlockPlacement` in Carpet Extra for
protocol **V2**, or Servux/Litemoretica for **V3**.

On a server running neither, that fails, and it fails in a specific way. Since **1.18.2** vanilla
validates that a click actually lands on the block it claims to be on. An encoded click position
does not — it is metres outside. The server discards the placement, and you get a **ghost block**.

Litematica knows this, which is why `easyPlaceProtocolVersion` on **Auto** drops to **Slabs Only**
the moment it fails to detect Carpet or Servux. Slabs Only encodes nothing, so nothing is rejected —
but nothing is communicated either. Orientation falls back to what a vanilla server works out on its
own: the face that was clicked, and **where the player happens to be looking**.

Litematica sets the clicked face for the few blocks that read it. **It never touches the look
direction.** That is the whole bug.

| Block | What decides its orientation | On a vanilla server |
|:--|:--|:--|
| Slab, trapdoor half | clicked face | ✅ Litematica sets it |
| Torch, wall sign, banner | clicked face | ✅ Litematica sets it |
| **Stairs** | player's yaw | ❌ faces wherever you stand |
| **Hopper** | clicked face | ❌ not set — feeds the wrong way |
| **Log, pillar, chain** | clicked face | ❌ not set — wrong axis |
| **Furnace, chest, anvil** | player's yaw | ❌ faces wherever you stand |
| **Observer, piston, dropper** | player's yaw *and* pitch | ❌ faces wherever you stand |
| **Lantern** | clicked face | ❌ stands when it should hang |
| **Door** | yaw, and where on the block | ❌ wrong side, wrong hinge |
| **Sign, banner** | yaw, to a sixteenth of a turn | ❌ wrong rotation |

## What this does instead

It answers with the same two inputs, rather than trying to talk past them.

A vanilla server decides a block's facing for itself, from the state the player appears to be in
when the placement arrives, and a client-side mod cannot overrule that. So this does not try to. For
the one placement Litematica is making, it works out **where you would have to be looking, and which
part of which face you would have to have clicked**, for the server to produce the wanted state by
its own ordinary rules — then claims exactly that, lets the placement happen, and tells the truth
again immediately.

Three packets, in a fixed order, all three of them things a player could genuinely have sent:

```
  →  rotation packet       the claimed look direction
  →  placement             Litematica's, with the claimed click
  →  rotation packet       your real look direction
```

Nothing is encoded. Nothing lands outside the block it claims to be on. There is nothing for the
server to reject.

**Your camera does not move.** The claim is applied to the client's own copy of the player for the
length of one method call, well inside the tick — so the block your client predicts is the block the
server is about to place, and there is no correction to watch arrive.

<details>
<summary><b>How the combination is found</b> — it is searched for, not looked up</summary>

<br>

Candidates are tried by **asking the block itself what it would place** — the same question the
server is about to answer — so a combination that would not really produce the wanted state is never
claimed.

**Candidate rotations** come from the directions the wanted state actually names, plus their
opposites (a furnace faces the player, a piston faces away; trying both means neither has to be
known in advance), plus every cardinal look, plus a sixteen-step sweep for blocks that store a
rotation finer than six directions.

**Candidate clicks** are ordinary points on each face — of the block Litematica chose, and of the
target itself where those differ. Every one is checked against **where the block would actually
land**, so a click that would move the block is discarded rather than used. Where Litematica clicked
a neighbouring block to support what it is placing, the face is carrying the position, and those
candidates drop out on their own.

Litematica's own click is tried first, against every rotation, before any other click is considered.
A rotation claim overrides nothing; a different click overrides a choice Litematica made
deliberately. The cheaper knob gets turned first.

**Which properties count as orientation** is decided by name and by type — `facing`, `axis`, `half`,
`hinge`, `orientation`, `rotation`, `hanging`, anything valued as a direction — not by a list of
blocks. Modded blocks are handled on exactly the same terms as vanilla ones.

**A property that no candidate manages to change was never this placement's to decide.** A chest
half that only a neighbouring chest can settle, a bed's head, a door's upper half — these are
dropped before matching, rather than being allowed to declare a block impossible.

</details>

<details>
<summary><b>When it stays out of the way</b></summary>

<br>

- **Single player.** Litematica's V3 protocol is honoured in full; there is nothing to fix.
- **Carpet or Servux servers.** Those protocols carry things a click and a look cannot — a
  repeater's delay, a comparator's mode. Taking over would trade a complete answer for a partial
  one. Litematica's own choice of protocol is read to tell these apart, so this needs no detection
  of its own and stays right when the server changes.
- **Blocks you place by hand.** The mod asks Litematica whether *it* is the one placing, and only
  ever acts when the answer is yes.
- **Blocks already landing correctly.** Your own rotation and Litematica's own click are the first
  candidate tried, so a block that needs nothing costs nothing and claims nothing.

</details>

<details>
<summary><b>Limits</b> — what it cannot do, and what it does instead</summary>

<br>

- **A hopper cannot be made to face up.** Vanilla does not allow it; the property that stores a
  hopper's facing has no `up` value at all.
- **Properties no click or look can reach are not reached.** A repeater's delay and a comparator's
  mode are set *after* placement, by using the block. This does not change that — it is what
  Carpet's protocol is for.
- Where a wanted orientation cannot be produced, the placement is **declined rather than made
  wrongly**, and the action bar names the block. A missing block shows in Litematica's overlay as
  something still to do; a wrong one has to be found and broken first. Litematica returns to the
  position on its own, so a block that becomes placeable later — once the neighbour it needed is
  there — is picked up without anything to retry by hand.

</details>

---

## Installing

Drop the jar in `.minecraft/mods` alongside:

| | |
|:--|:--|
| [Fabric Loader](https://fabricmc.net/use/) | 0.19.3+ |
| [Fabric API](https://modrinth.com/mod/fabric-api) | |
| [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) | |
| **[Litematica](https://github.com/sakura-ryoko/litematica)** + **MaLiLib** | **0.28.0+ — required** |

> [!IMPORTANT]
> Litematica for Minecraft 26.x is the [**sakura-ryoko**](https://github.com/sakura-ryoko/litematica)
> continuation. masa's original tree stops at 1.21.1.

Then, in Litematica's config:

```
Generic ▸ easyPlaceProtocolVersion  ▸  Auto
```

**Leave it on Auto.** Auto is what lets Litematica tell a vanilla server from a modded one, and this
mod reads that decision to know whether to step in. Pinning it to V2 or V3 on a server that supports
neither produces ghost blocks that *no* client-side mod can fix, because the placement never arrives
at all.

## Using it

There is nothing to switch on. Litematica decides when Easy Place runs and what belongs where; this
steps in for the moment a block is placed, and only where the orientation would otherwise go unsaid.

A keybind to toggle it off and on lives under **Options ▸ Controls ▸ About Face Easy Place**. It
starts **unbound**, so it cannot collide with any of Litematica's own keys. That is the whole
configuration surface, by design.

## Troubleshooting

| What you see | What it means |
|:--|:--|
| Blocks never appear at all | `easyPlaceProtocolVersion` is pinned to V2 or V3 on a server that supports neither. Set it to **Auto**. |
| *"Litematica is using its V2 placement protocol…"* | Litematica detected Carpet or Servux and is handling orientation itself, so this mod stood aside. If blocks are landing fine, ignore it. |
| *"Cannot place … facing that way here"* | No legal click and look produces that orientation — a hopper facing up, most likely. The block is skipped rather than placed wrongly. |
| Nothing happens at all | Check the log for `About Face Easy Place: could not find …`, which means this Litematica build is one the mod cannot read, and it has stood down for the session. |

## Building

Requires **JDK 25** or newer.

```bash
./gradlew build      # jar lands in build/libs/
./gradlew runClient  # dev client
```

Litematica is a hard dependency at runtime but **not at build time**: the three things this mod asks
it are reached by reflection, over methods whose signatures contain no Minecraft types. That is
deliberate rather than a shortcut — no Litematica jar is needed to compile, no mapping mismatch is
possible between the two, and a Litematica that has moved underneath the mod produces one clear log
line and a mod that stands down, rather than a crash or a wrongly placed block.

### Verification

The placement search can be exercised without Minecraft at all:

```bash
cd verification && ./run.sh
```

Thirty-four checks against stand-in blocks written from vanilla's placement rules. Each one replays
the plan the search returned and confirms the block really lands in the wanted state, so a plan that
merely looks plausible does not pass. See [`verification/README.md`](verification/README.md) for
what this does and does not prove.

---

## Licence and origin

**MIT** — see [LICENSE](LICENSE).

Written from scratch. Contains no code from Litematica or from any other project.

A companion to [**About Face**](https://github.com/SkyStormer1/AboutFace), and it shares that mod's
approach: present the server with the inputs that produce the answer you want, rather than argue
with the answer it gives. The underlying techniques — claiming a rotation, and choosing which part
of which face to claim a click on — are long-standing, widely documented client-side Minecraft
methods, not inventions of any one mod. Several mods use them; none of their code is used here.

Litematica is by [masa](https://github.com/maruohon), maintained for modern versions by
[Sakura-Ryoko](https://github.com/sakura-ryoko). This mod is a plugin for their work, not a
replacement for it.

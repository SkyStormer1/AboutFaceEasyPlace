# About Face Easy Place: A Litematica Plugin

**Litematica's Easy Place, with the blocks facing the right way on a vanilla server.**

A small client-side Fabric mod for Minecraft 26.2 that does one thing: when Litematica's Easy Place
puts a block down on a server that does not run Carpet or Servux, this makes it land in the
orientation the schematic actually asks for. Stairs, slabs, hoppers, observers, pistons, furnaces,
logs, trapdoors, doors, glazed terracotta, signs — all of it.

Nothing is needed server-side. Litematica is required; this is a plugin for it, not a replacement.

---

## The problem

Easy Place has to tell the server not just *where* a block goes but *which way round*, and the
server it is talking to has no idea schematics exist. Litematica solves this by encoding the wanted
block state into the fractional part of the click position and having a server-side mod decode it —
`accurateBlockPlacement` in Carpet Extra for protocol V2, or Servux/Litemoretica for V3.

On a server running neither, that does not work, and it fails in a particular way. Since 1.18.2
vanilla validates that a click actually lands on the block it claims to be on, and an encoded click
position does not: it is metres outside. The server throws the placement away and the player gets a
ghost block.

Litematica knows this, which is why `easyPlaceProtocolVersion` on `Auto` drops to **Slabs Only** the
moment it fails to detect Carpet or Servux. Slabs Only encodes nothing, so nothing gets rejected —
but nothing gets communicated either. Orientation falls back to what a vanilla server would work out
on its own: the face that was clicked, and **where the player happens to be looking**.

Litematica sets the clicked face for the few blocks that read it. It never touches the look
direction. That is the whole bug:

| Block | What decides its facing | On a vanilla server |
|---|---|---|
| Slab, trapdoor (top/bottom) | clicked face | ✅ Litematica sets it |
| Torch, wall sign, banner | clicked face | ✅ Litematica sets it |
| **Stairs** | **player's yaw** | ❌ faces wherever you stand |
| **Hopper** | **clicked face** | ❌ not set — feeds the wrong way |
| **Log, pillar** | **clicked face** | ❌ not set — wrong axis |
| **Furnace, chest, anvil** | **player's yaw** | ❌ faces wherever you stand |
| **Observer, piston, dropper** | **player's yaw and pitch** | ❌ faces wherever you stand |
| **Door** | **yaw, and where on the block** | ❌ wrong side, wrong hinge |
| **Sign, banner (standing)** | **yaw, to a sixteenth** | ❌ wrong rotation |

## What this does

It answers with the same two inputs, instead of trying to talk past them.

A vanilla server decides a block's facing for itself, from the state the player appears to be in
when the placement arrives, and a client-side mod cannot overrule that. So this does not try to.
For the one placement Litematica is making, it works out **where the player would have to be looking
and which part of which face they would have to have clicked** for the server to produce the wanted
state by its own ordinary rules — then claims exactly that, lets the placement happen, and tells the
truth again immediately.

Three packets, in a fixed order, all three of them things a player could genuinely have sent:

1. a rotation packet with the claimed look direction,
2. Litematica's placement, with the claimed click,
3. a rotation packet with the player's real look direction.

Nothing is encoded, nothing lands outside the block it claims to be on, and there is nothing for the
server to reject. The camera does not move — the claim is applied to the client's own copy of the
player for the length of one method call, well inside the tick, so the block the client predicts
locally is the block the server is about to place and there is no correction to watch arrive.

### How the combination is found

It is not guessed at, and it is not a table of blocks.

Candidates are tried by asking the block itself what it *would* place — the same question the server
will answer — so a combination that would not really produce the wanted state is never claimed.
Candidate rotations come from the directions the wanted state actually names, plus their opposites
(a furnace faces the player, a piston faces away, and trying both means neither has to be known in
advance), plus every cardinal look, plus a sixteen-step sweep for the blocks that store a rotation
finer than six directions. Candidate clicks are ordinary points on each face of the same block.

Every candidate is checked against where the block would actually land, so a click that would move
the block is discarded rather than used. Where Litematica has clicked a neighbouring block to
support what it is placing — as it does for torches and wall signs — the face is carrying the
position, and those candidates drop out on their own.

Which properties count as orientation is decided by name and by type (`facing`, `axis`, `half`,
`hinge`, `orientation`, `rotation`, anything valued as a direction), not by a list of blocks, so
modded blocks are handled on the same terms as vanilla ones. A property that no candidate placement
manages to change was never this placement's to decide — a chest half that only a neighbouring chest
can settle, a bed's head, a door's upper half — and is dropped before matching rather than being
allowed to declare the block impossible.

### When it stays out of the way

- **Single player.** Litematica's V3 protocol is honoured in full; there is nothing to fix.
- **Carpet or Servux servers.** Those protocols carry things a click and a look cannot — a
  repeater's delay, a comparator's mode. Taking over would trade a complete answer for a partial one.
  Litematica's own choice of protocol is read to tell these apart, so this needs no detection of its
  own and stays right when the server changes.
- **Blocks the player places by hand.** The mod asks Litematica whether *it* is the one placing, and
  only ever acts when the answer is yes.
- **Blocks that are already landing correctly.** The player's own rotation and Litematica's own click
  are the first candidate tried, so a block that needs nothing costs nothing and claims nothing.

### Limits

- **A hopper cannot be made to face up.** Vanilla does not allow it — the property that stores a
  hopper's facing has no `up` value at all.
- **Properties that no click or look can reach are not reached.** A repeater's delay and a
  comparator's mode are set after placement, by using the block, not during it. This does not change
  that; it is what Carpet's protocol is for.
- Where a wanted orientation cannot be produced, the placement is **declined rather than made
  wrongly**, and the action bar says which block. A block that is missing shows up in Litematica's
  overlay as something still to do; a block that is wrong has to be found and broken first.
  Litematica will come back to the position on its own, so a block that becomes placeable later —
  once the neighbour it needed is there — is picked up without anything to retry by hand.

## Installing

Drop the jar in `.minecraft/mods` alongside:

- [Fabric Loader](https://fabricmc.net/use/) 0.19.3+
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- **[Litematica](https://github.com/sakura-ryoko/litematica) 0.28.0+ and MaLiLib** — required

Litematica for Minecraft 26.x is the [sakura-ryoko](https://github.com/sakura-ryoko/litematica)
continuation; masa's original tree stops at 1.21.1.

Then set Litematica's `easyPlaceProtocolVersion` (Generic tab) to **Auto** and leave it there. Auto
is what lets Litematica tell a vanilla server from a modded one, and this mod reads that decision to
know whether to step in. Pinning it to V2 or V3 on a server that supports neither produces ghost
blocks that no client-side mod can fix, because the placement never arrives.

## Configuration

There is a keybind to toggle the mod off and on, under **Options → Controls → About Face Easy
Place**. It starts unbound so that it cannot collide with any of Litematica's own keys. That is the
whole configuration surface, by design.

## Building

Requires JDK 25 or newer.

```bash
./gradlew build
```

The jar lands in `build/libs/`. For a dev client, `./gradlew runClient`.

Litematica is a hard dependency at runtime but not at build time: the three things this mod asks it
are reached by reflection, over methods whose signatures contain no Minecraft types. That is
deliberate rather than a shortcut — it means no Litematica jar is needed to compile, no mapping
mismatch is possible between the two, and a Litematica that has moved underneath the mod produces
one clear log line and a mod that stands down, rather than a crash or a wrongly placed block.

See [`verification/`](verification/) for a check on the placement search that runs without
Minecraft.

## Licence and origin

MIT — see [LICENSE](LICENSE).

This mod is written from scratch and contains no code from Litematica or from any other project. It
is a companion to [About Face](https://github.com/SkyStormer1/AboutFace) and shares its approach:
present the server with the inputs that produce the wanted answer, rather than argue with the answer
it gives. The underlying techniques — claiming a rotation, and choosing which part of which face to
claim a click on — are long-standing, widely documented client-side Minecraft methods, not
inventions of any one mod. Several mods use them; none of their code is used here.

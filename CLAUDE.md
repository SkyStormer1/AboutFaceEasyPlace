# About Face Easy Place — working notes

A client-side Fabric mod for Minecraft 26.2. It is a **plugin for Litematica**: when Litematica's
Easy Place puts a block down on a server that runs neither Carpet nor Servux, this makes it land in
the orientation the schematic asks for. Stairs, slabs, hoppers, logs, observers, doors, lanterns,
signs. Nothing is needed server-side.

Read `README.md` for what it does and why. This file is for whoever works on it next.

---

## Status: written, reviewed, never run

**The mod has never been compiled against real Minecraft, and has never been loaded into the game.**

Everything so far was written in a sandbox with no access to `maven.fabricmc.net`, Modrinth or
masa's maven, and with JDK 21 where the project needs 25 — so Loom could not resolve, Minecraft
could not be fetched, and `./gradlew build` was never run once. What *has* been verified:

- Kotlin and Java compile cleanly against hand-written stubs (`verification/stubs/`).
- The placement search passes 37 checks (`verification/run.sh`).
- Every Minecraft API name used was checked against real Litematica 26.2 source or against the
  author's working [About Face](https://github.com/SkyStormer1/AboutFace) mod — not from memory.

So the API surface is *evidenced* but not *proven*. Treat the first real build as a first build.

### Do this first, on a machine that can build

```bash
./gradlew build          # needs JDK 25+
cd verification && ./run.sh   # needs kotlinc; no Minecraft required
```

If the build fails, look in this order:

1. **`src/main/java/.../gui/ConfigScreen.java`** — by far the most likely. Vanilla GUI widgets
   (`CycleButton.onOffBuilder`, `Button.builder().bounds()`, `StringWidget`) were the only APIs with
   no 26.2 source to check against; Litematica uses MaLiLib's GUI framework, so it gave no example.
   **It is contained**: delete `src/main/java/.../gui/` and the `modmenu` entrypoint from
   `fabric.mod.json` and you have a fully working mod with the keybind and config file intact. Do
   not let a settings screen block testing the actual fix.
2. **`MultiPlayerGameModeMixin`** — the `useItemOn` descriptor. Taken from Litematica 26.2's own
   call site, so it should be right, but a mixin target failure is loud and lands here.
3. **`gradle.properties`** — `litematica_version=0.28.0` is an estimate of the first 26.x release
   carrying `EasyPlaceUtils`. If the loader rejects the dependency, match what you actually run.
   `modmenu_version=20.0.1` was copied from Litematica's own 26.2 build config.

### Then test it in game

Vanilla server, Litematica's `easyPlaceProtocolVersion` on **Auto**, `easyPlaceMode` on, activation
key held. Turn on **Log every placement** first (Mod Menu, or `verboseLogging` in
`config/aboutfaceeasyplace.json`) — the log is designed to make a failure diagnosable in one pass.

- **Staircase** — the headline case; exercises rotation claiming.
- **Hopper** — exercises the clicked-face half of the search, a different code path.
- **Lantern** — exercises the boolean-orientation path, which was broken until a late review pass.

The audit line is the one to read. Half a second after each placement the mod compares what is
actually in the world against what it claimed, so `the server settled on X, not the Y that was
claimed` means the plan was right and the server disagreed — a different bug from a wrong plan.

---

## How it works, in one paragraph

A vanilla server decides orientation from the player's rotation and the clicked face. Litematica
encodes the wanted state into the click position instead, which vanilla has rejected as out of
bounds since 1.18.2, so on a plain server Litematica falls back to a protocol that says nothing and
orientation comes out of whatever the player happened to be looking at. This mod searches for a
rotation and a click that make vanilla's *own* rules produce the wanted state, claims them for the
length of one placement, and tells the truth again immediately. Candidates are validated by asking
the block what it would place — the same question the server will answer — and every candidate is
checked against where the block would actually land, so nothing ever moves.

## Files

| File | What it is |
|:--|:--|
| `Aligner.kt` | The search. The heart of the mod; read this first. |
| `EasyPlaceHook.kt` | The three packets, the reentrancy guard, the rotation claim. |
| `Litematica.kt` | The *only* contact with Litematica. Stack gate + two reflective calls. |
| `Engagement.kt` | Whether the mod has any business in this placement at all. |
| `PlacementAudit.kt` | Reads back what the server actually did. Verbose logging only. |
| `Log.kt`, `Messages.kt`, `Config.kt` | Logging, action bar, settings. |
| `mixin/MultiPlayerGameModeMixin.java` | The single point of contact with Minecraft. |
| `BlockStates.java` | One operation in Java because Kotlin cannot name `Property<?>`'s type. |
| `verification/` | Runs the search against stand-in blocks, without Minecraft. |

## Litematica facts that cost real research

Do not re-derive these; several were only found by reading the source.

- **The fork matters.** masa's tree stops at 1.21.1. Minecraft 26.x is
  [sakura-ryoko/litematica](https://github.com/sakura-ryoko/litematica), branch `LTS/26.2`. It uses
  **Mojang mappings**, same as this mod.
- **There are two Easy Place implementations.** `EasyPlaceUtils` (new) and `WorldUtils` (old,
  `@Deprecated`). Which one runs depends on `easyPlacePostRewrite`, **which defaults to `false`** —
  so the old path is what most people are running. Only the new one raises an `isHandling` flag.
  Gating on that flag made the mod completely inert on a stock install; that bug shipped and was
  caught later. The gate is now a stack walk, which catches both.
- **Litematica injects at HEAD of the same `useItemOn`, also cancellable.** Both mixin orderings
  were traced and both are correct: the nested placement Litematica makes is the only call either
  mod's gate accepts. Not luck, but worth knowing before touching the hook.
- **Not every Litematica placement is Easy Place.** `TaskPasteSchematicPerChunkCommand` places a
  block at a scratch position to capture a block entity, then removes it. The gate accepts only
  `fi.dy.masa.litematica.util.` for this reason, and logs once for anything else.
- **Wall-mounted blocks are deliberately skipped.** A wall torch is placed from a torch item, so
  `wanted.block !== block` and the mod stays out. Litematica already aims those correctly itself.
- **`hanging` is orientation.** A lantern is the one common block storing orientation as a boolean.
  Litematica's own orientation list has it; a direction-and-name rule alone misses it.
- **`shape` means two things.** A rail's is set by the placement; a stair's by its neighbours. It is
  only treated as orientation when the block has nothing else — see `PREFERRED_PROPERTIES`.

## Conventions

- **No compile-time dependency on Litematica.** The three things it is asked take no Minecraft types
  in their signatures, so reflection is exact and no mapping mismatch is possible. Keep it that way.
- **`verification/run.sh` must pass before committing.** If a change makes a case fail, the case is
  probably right. When adding a fix, add a case and confirm it fails without the fix — one already
  in the suite was verified that way.
- **Comments explain why, not what.** The existing ones set the bar; match it rather than adding
  narration.
- **Never let the mod place a block wrongly.** Where orientation cannot be reached it declines and
  says so. A missing block shows in Litematica's overlay; a wrong one must be found and broken.

## Repository

`SkyStormer1/AboutFaceEasyPlace`, **private**, default branch `main`. Intended for eventual public
release — there is a `> [!NOTE]` near the top of `README.md` saying it is untested, which should be
deleted once it has been round the block, and the repo description must be set by hand (the API
token used so far could not write repository settings).

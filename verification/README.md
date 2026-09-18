# Verification

The mod needs Loom, a Minecraft jar and a Litematica install to build and run. The part of it worth
checking hardest does not: the search that decides which rotation and which click produce a wanted
block state is ordinary Kotlin over a dozen Minecraft types.

So those types are stubbed in `stubs/`, `SearchVerification.kt` stands in for the vanilla blocks
whose placement rules matter — stairs, hoppers, furnaces, observers, logs — and the search is run
against them directly.

```bash
./run.sh
```

Each case ends by replaying the plan the search returned and checking that the block really lands
in the wanted state, so a plan that merely looks plausible does not pass.

**What this does not prove.** The stand-in blocks are written from vanilla's placement rules, not
taken from Minecraft, and the stubs are written to the signatures Minecraft 26.2 is understood to
have. If a stand-in is wrong, the search will agree with it and this will still pass. It is a check
on the search, not on the mappings.

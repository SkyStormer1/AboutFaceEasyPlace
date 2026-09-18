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

Thirty-six cases run, plus a check on the logging. Each ends by replaying the plan the search returned and checking that the
block really lands in the wanted state, so a plan that merely looks plausible does not pass.

The cases are chosen to cover each way a block can take its orientation: from the look direction
(stairs, furnaces), from the clicked face (hoppers, logs, lanterns), from both at once (observers),
from a boolean rather than a direction (lanterns), from a click on a neighbouring block rather than
the target (a hopper placed against the block below it), and from nothing at all (stone). Every
horizontal facing is checked from six starting rotations, which is where an off-by-one in the yaw
convention would show up and a single spot check would not.

The logging is exercised too. Every line the search and the audit produce is rendered, and the
placeholders are counted against the arguments — a log line with the wrong number of `{}` is a bug
that stays invisible until the one moment it fires, which is the moment someone is already trying
to work out why a block went down crooked.

**What this does not prove.** The stand-in blocks are written from vanilla's placement rules, not
taken from Minecraft, and the stubs are written to the signatures Minecraft 26.2 is understood to
have. If a stand-in is wrong, the search will agree with it and this will still pass. It is a check
on the search, not on the mappings.

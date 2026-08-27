<!--
Thanks for contributing to Lune. Fill in what applies, delete what does not.
A PR that explains what it changed and how it was watched gets merged much faster
than one that does not.
-->

## What this changes

<!-- Two or three sentences. What was wrong, missing or annoying, and what this does
     about it. Write it for someone who has not seen the code. -->

Fixes #<!-- issue number, or delete this line -->

## Type of change

- [ ] Bug fix — the bot did the wrong thing and now does the right thing
- [ ] Behaviour change — the bot did something reasonable and now does something better
- [ ] New task or command
- [ ] Routine / node editor change
- [ ] Interface (panel, tabs, mascot, overlay)
- [ ] Performance — the same behaviour, cheaper
- [ ] Refactor — no behaviour change at all
- [ ] Build, loader or tooling
- [ ] Documentation
- [ ] Tests only

## What it touches

<!-- Tick everything the change reaches, not just where you edited. -->

- [ ] Tasks (`bot/task`) — which: <!-- Mine, Chop Wood, Explore, Find, Harvest, Kill,
      Loot, Fish, Tunnel, Stripmine, Bridge, Deposit, Smelt, Complete the Game, ... -->
- [ ] Pathfinding and movement (`bot/path`)
- [ ] Vision, scanning and target choice (`bot/util`, `bot/catalog`)
- [ ] Knowledge and memory (`bot/knowledge`, `bot/memory`)
- [ ] Learning (`bot/learning`, `lune-learning.json`)
- [ ] Routines (`routine`)
- [ ] Interface (`client/gui`)
- [ ] Config (`config`)
- [ ] Waypoints (`waypoint`)
- [ ] Platform / loader glue (`platform`, `fabric`, `neoforge`, `forge`)

## Shared behaviour

<!-- Lune's jobs share their thinking. Chopping a tree is Chop Wood *and* the
     speedrun's wood phase; searching is Explore, Mine and Find; collecting is Loot
     and Mine's own sweep. A fix landed in one job and not its siblings leaves the
     bot visibly smarter in one place and dumber in the next. -->

- [ ] This change is local — it cannot affect any other job
- [ ] This change touches shared code, and I checked the other jobs that use it

Reached: <!-- which jobs this change affects -->
Deliberately not reached: <!-- and which it does not, and why -->

## Playing fair

<!-- Lune's default playstyle is human-like, not omniscient: she does not target
     blocks she has no way to see. Delete this section if the change has nothing
     to do with picking targets. -->

- [ ] Targets are filtered through `Vision` before the bot acts on them
- [ ] `omniscientMining` / `omniscientHarvesting` still default to `false` and still
      gate any see-through behaviour
- [ ] When nothing visible is left, the task says so instead of falling back to
      hidden targets

## How it was tested

- [ ] `./gradlew :common:test` passes
- [ ] `./gradlew build` passes
- [ ] Ran it in a dev client (`./gradlew :neoforge:runClient` or `:fabric:runClient`)

Loaders it ran on: <!-- NeoForge / Fabric / Forge / not loader-specific -->
Minecraft version: <!-- e.g. 26.1.2 -->

**What you watched it do:**

<!-- The important part. Set the bot a job, watch it, and say what happened —
     "mined 64 iron in a fresh world, took a staircase down instead of strip
     mining, never got stuck in water". If it used to fail and now does not,
     say what the failure looked like. -->

## Screenshots or video

<!-- Required for interface changes, welcome for everything else. Before/after if
     you have both. -->

## Compatibility

- [ ] Existing configs, routines, waypoints and learning profiles still load
- [ ] This changes a saved format <!-- if so, say what happens to an old file -->

## Anything you are unsure about

<!-- Say so here. A PR that flags its own weak spot is easier to review, not
     harder. -->

## Contribution terms

- [ ] I have read section 4 of the
      [LICENSE](https://github.com/EtkaPerry/LogicUsingNodeEngine/blob/main/LICENSE)
      and I agree to it for this contribution: I grant the Author a perpetual,
      worldwide, irrevocable, royalty-free, exclusive and sublicensable license to
      it, and will sign an assignment if one is needed. The work is my own and mine
      to give.

<!--
That last box is not a formality — an unticked box means the PR cannot be merged.
It does not apply to issues, feature requests or ideas, which stay yours.
-->

# Lune

> **L**ogic **U**sing **N**ode **E**ngine

A client-side automation mod for Minecraft. Wire a job together out of nodes, press play, and go
do something else — Lune walks, digs, fights and crafts on her own, survives (and sometimes can even clutch a
water bucket mlg if she has one).

---

## Why I built this

I originally created Lune because I wanted to build a massive house out of Polished Deepslate.
Working 10 hours a day meant that manually collecting thousands of blocks felt like a waste of my
limited free time, but I didn't want to cheat and simply spawn the items in either. Lune was the
compromise: an honest worker to mine the stone for me while I stepped away from the keyboard.

---

Built for **NeoForge** (primary), **Forge**, and **Fabric**, sharing one module that holds
essentially all the logic.

---

## Meet Lune

<img src="https://github.com/EtkaPerry/LogicUsingNodeEngine/raw/main/docs/lune-logo.svg" alt="Lune, the floating soul mascot" width="160" align="left" hspace="24">

Lune is the little floating soul who lives in the control panel. She follows you in her little comfortable interface and helps with the tasks you create together.

She is more than decoration. Lune shows the current job and its progress, celebrates milestones,
and notices when the bot is thinking, working, waiting, blocked, in danger, successful, out of
inventory space, missing materials, or paused by the player.

In the Task tab she may suggest practical improvements: a sensible target for a large gathering
job, a tool or a meal packed before a long one, a safety monitor, torches, somewhere to sleep, or a
wire that points at a card which no longer exists. Every change is previewed first and only happens
after you accept it, and an accepted one can be taken straight back with Undo. Suggestions can be
postponed, muted for one task, muted by type, or restored later from Config. Lune can also be made
quieter, put to sleep, resized, or hidden entirely.

One kind of remark reaches you anywhere, not just on the Task tab: when a step of the running task
is measurably what is costing your frame rate. That one is not an unsolicited idea — it explains
something already happening to your game, and the Task screen is precisely where you are not
sitting when the world starts coming apart. See
[When a task eats your frame rate](#when-a-task-eats-your-frame-rate).

That logo is the same layered design used in the interface: dark shell, animated golden soul, and
one watchful eye.

<br clear="left">

---

## Requirements

| | |
| Minecraft | 26.1.2 |
| Java | 25 |
| Loader | NeoForge, Forge, or Fabric with Fabric API |

Client-side only — the server never sees it. **Most multiplayer servers ban automation.** Check the
rules where you play, and keep backups: this thing digs, and it will happily dig somewhere you did
not want a hole.

## Keys

| Key | Action |
| --- | --- |
| `J` | Open the control panel |
| `K` | Pause / resume |
| `Shift+K` | Stop everything, now |
| `F6` | Debug overlay |
| `F7` / `F8` | Approve or reject the tactic the learner just used |

All five sit under their own **Lune** heading in the vanilla Controls screen rather than scattered
through Miscellaneous, and rebind like anything else.

## The panel

- **Main** — what the bot is doing right now, plus Pause / Clear / Stop. With nothing queued, the
  lower half lists your saved tasks instead of a box telling you to go to another tab: click one
  and it runs.
- **Task** — the node editor. This is where the work happens. The Run button reports the state it
  is in and changes it: it says Stop while the task you are looking at is the one running, so you
  no longer have to leave the screen that started a task in order to stop it.
- **Config** — movement, safety limits, appearance, debug, learning, and advanced world access.
  Three footer buttons put things back: **Restore Lune suggestions**, **Restore default tasks**,
  and **Reset config settings**, which asks twice because it cannot be undone and which never
  touches your saved tasks or waypoints — those are content, not settings.
- **Waypoints** — named places. Leave the box blank to save where you stand. In single-player,
  they are stored inside that world's `lune/` folder, so moving or copying the world keeps its
  waypoints while another world cannot accidentally use them. Multiplayer waypoints are isolated
  by server address.

Config also carries how the canvas looks. **Node colours** picks the family cards are drawn from —
cards are shaded by the role they play in the circuit, not by which class they belong to. **Pin
colours** is a separate setting because pin colour is information rather than taste: Success and
Fail default to green and red, the one pair red-green colour blindness makes hardest to tell apart,
so the alternative swaps every pin and wire to Okabe-Ito. Pins keep their written labels either
way. **Close panel on Run** decides whether starting a task hands the screen back to the game.

The optional omniscient mining and harvesting settings are available only in a single-player world
(including a world opened to LAN) or to a player with the server's vanilla operator permission. On
other multiplayer accounts they are locked and runtime behavior remains human-vision only, even if
the local config file was edited by hand.

## Task

A task is a graph of independently powered circuits made of nodes. Each node is one command; each
command reports success or failure when it finishes, and that outcome *is* the branch — there is no
separate condition language to learn.

- Nodes with nothing wired to them just run in order.
- **START** is the ordinary entry point, and Lune adds one to any task that has no source of its
  own. Connect its Success pin to the first action; it sends that signal once.
- **Always** is an input-less power source. It holds the branch wired to it energised for as long
  as the task runs, alongside START and alongside every other source — no circuit outranks another.
- **Pulse** is a clock: one signal every few seconds, five by default. It used to be a setting on
  Always, and the two are different jobs wearing one name. Always is a switch held on; Pulse is a
  metronome, and "every ten seconds" is the whole reason to reach for it, so it says so on the card.
- Green **Success** and red **Fail** pins send the run somewhere else.
- An edge pointing back at an earlier node is a loop.
- The amber **While** pin keeps its connected companion circuit active for as long as the main
  node is running. When the main node finishes, the While signal stops.
- **Signal Relay** is a neutral pulse junction. It starts with one numbered input and one numbered
  output; use the **Inputs** and **Outputs** selectors to add ports. A pulse arriving at any input
  is forwarded through every connected output, including to another relay.
- **Timer** waits for the selected number of seconds after receiving a pulse, then forwards one.
  It never creates a pulse by itself; its repeat setting can forward it multiple times or forever.
- **Counter** consumes incoming pulses and forwards one after every selected number of pulses,
  then starts counting again.
- **Observer** watches another card, wired into the pin on its left, and sends a pulse whenever that
  card's power changes — once when it lights up, once when it goes dark. No signal travels along
  that wire; it is a reading, not an edge, which is why the Observer has no settings of its own.
  Its first sample only establishes a baseline, so nothing fires the moment a task starts.
- **Button** is a manual event source. Select it and press its editor **Press** control to send one
  pulse through its outputs.
- **End** consumes a pulse and finishes that circuit without needing an output.

The repeat control counts how many times a node task is started. A Mine node's block goal is a
separate setting: `x1` means one Mine task run, not one block, so a Mine run with no block limit can
still continue until no matching blocks remain. Lune's quantity suggestion calls this out before
offering a limit. The `x` chip on a card is editable in place — click it and type — and on a Pulse
the same chip sets its interval instead.

Export copies a task to your clipboard as JSON and Import reads one back. Blocks and mobs are
stored as names like `minecraft:iron_ore`, so a task still works on someone else's mod set.

### Working the canvas

| Input | What it does |
| --- | --- |
| Drag a palette tile | Drops that card where you release it, rather than at the end of the list |
| Middle-drag, or scroll | Pan. `Ctrl`+scroll zooms about the cursor, `Ctrl`+`0` puts it back to 1:1 |
| Left-drag on empty space | Rubber-band select. `Ctrl`+`A` takes everything, `Esc` clears it |
| Left-drag a wire | Reroutes it, dropping a handle where you pulled. Right-click a handle to remove it |
| Right-click a card | Card menu, including Delete. Right-click a wire cuts it |
| `Delete` | Removes the whole selection |
| Undo / Redo | Buttons beside the task list; they cover the wiring, the cards and their routes |

A dragged card near the rim pulls the view after it. **Layout** re-tidies the graph, and **Map**
toggles a draggable minimap for the long tasks where the whole thing stops fitting on one screen.
When the tab gets too narrow for three panes the task list folds away behind a **Tasks** button,
because you pick a task once and then spend the rest of the session in the canvas and the palette.

While a task runs, the canvas shows it: powered cards glow, and a spark travels each live wire in
the direction the signal went. A task has more than one circuit alive at a time — START drives one,
every Always, Pulse, Observer and Button drives its own — so all of them light up, not just the
lane the runner happens to be in. Drawing only the primary lane is what used to make a perfectly
healthy Always branch look dead.

The palette also carries **blueprints**: small pre-wired groups like *Find & mine diamond* or *Get
iron pick* that drop in as ordinary cards you can then take apart.

Any item field opens a picker showing what you are actually carrying, including the contents of
shulker boxes, bundles and any modded backpack that stores its items the vanilla way — with a
search over the full registry behind it. Cycling one item at a time through a modded registry was
never going to work, and it asked the wrong question anyway: you are not looking for *an* item, you
are looking for *that* pickaxe.

### What ships with it

Six default jobs are created the first time you run Lune. They are ordered from a small practical
chain to the most ambitious mission the Blueprint system can currently express, and then close on a
long one that introduces no new cards at all and shows how far the plain ones carry.

| # | Job | What it does |
| ---: | --- | --- |
| 1 | **Woodland Cleanup** | Chops a measured batch of logs, then sweeps up every drop |
| 2 | **Regenerative Farm Shift** | Harvests mature crops, replants them, stores the yield when a container is present, and eats when needed |
| 3 | **Ironworks Supply Run** | Checks existing stock, provisions a pickaxe, loops through human-like prospecting until the iron quota is met, then smelts and stores it |
| 4 | **Deepcore Diamond Expedition** | Chooses the best carried pick, falls back to forging one, shares one ore target through data wires, and runs two bounded mining passes under a safety monitor |
| 5 | **Dragonfall Mission Control** | Runs the complete-game route, hands the win into a post-fight loot sweep, keeps timed field meals and a While safety circuit alive, and finishes with a manual emergency brake plus an Observer/Counter/Relay debrief |
| 6 | **A Day in the Life** | Forty-one cards and not one new kind: dawn field work, a morning of wood, stone and coal, a midday iron run and lunch, a dusk sort into storage, and a night of clearing the yard before bed. Every phase hands forward, so its branches skip work that is already done instead of looping back |

Delete any you don't want; they stay deleted until you choose **Restore default tasks** in Config.

## What it can do

**Gathering** — Mine, Chop Wood, Harvest crops, Loot drops, Hunt Sheep for wool.

**Tools** — Get Tools crafts what you ask for and gathers its own materials on the way. Ask for an
iron pickaxe with an empty inventory and it fells trees, digs a stone staircase, crafts a furnace,
mines ore and smelts it.

**Movement** — Walk and Run by facing, compass direction or exact coordinates. Go to Waypoint,
Bridge across gaps, Tunnel, Stripmine, Boat across water.

**Combat** — Kill any mob you pick, with shield and weapon handling. Hunt Creepers, Skeletons and
Endermen for their drops.

**Survival** — Eat, Sleep (finds sheep, makes a bed, waits for dusk), and Self Preservation as an
independent background circuit.

**Boundaries** — Stay Near is a companion circuit like Self Preservation: wire it to a While pin,
or drop it into an Always circuit, and the bot keeps inside a radius of where the run started or of
a waypoint. While it is active, searches stop offering work on the far side of the line, so the bot
mostly never leaves rather than being walked back. The centre is taken once per run, so a long task
cannot creep.

**Storage** — Deposit into nearby chests, Smelt in a furnace.

**Inventory** — Select from Inventory puts a chosen item in hand and keeps it there. "A diamond
pickaxe" is rarely what anyone means: they mean *the enchanted one*, because the next step is Silk
Touch, or *the worn one*, because the point is to use it up before it is lost. So it filters on
enchantment and on remaining durability and picks between the survivors deliberately. Set it to
`x∞` and it becomes a watch — it takes the Fail branch the moment holding that item stops being
possible, which is the signal to go and fetch a replacement.

**Session** — Stop the Game ends the session at the depth you choose: pause it, back out to the
title screen, or quit to desktop.

**Logic** — Check Item Count, Check Player health/hunger/air, Check Distance to a waypoint, and Run
Task so one task can hand off to another.

Block and mob pickers are read from the live registry, so another mod's ores and crops appear in
them without Lune knowing that mod exists.

### How it fights

Lune is not a good fighter. She closes, aims, swings, and takes hits a decent player would have
stepped away from. There is no circling, no combo sense, no reading a creeper's fuse and leaving at
the right moment — hit-and-run backs off after a swing, and that is about the depth of it.

So the fight is tilted her way in a few small places, on purpose, because she needs the help:

- **The swing lands on the cooldown, every time.** She waits for the attack meter to refill and
  swings the moment it does. Players mistime that constantly. She does not.
- **Aim is a cone, not a crosshair.** Anything within fifteen degrees counts as aimed, so a swing is
  never lost to being a pixel off.
- **The opening critical is never wasted.** She checks what vanilla actually wants for the 1.5x —
  falling, off the ground, not on a ladder, not in water, not sprinting — and only jumps when the
  hit will really crit.
- **Danger gets a faster head.** The camera is eased and rate-limited like a hand on a mouse, but a
  creeper closing, an arrow already in flight or lava underfoot lifts that limit. Reaction time is
  the one place she is plainly better than you.

And a few places she is held back on purpose:

- The camera turns at a capped speed and eases in and out. It never snaps onto a target.
- The jump-crit is the opening hit only. Bunny-hopping before every swing would be free damage, and
  it reads as a bot from thirty blocks away.

**There is no PvP, and there probably never will be.** The Kill picker is a fixed list of mobs and
the player is not on it. Everything above is fair enough against a zombie and not fair at all
against a person, which is exactly why that list is fixed.

## When a task eats your frame rate

A task set to repeat forever can spend its life searching a place that has nothing in it: finish,
be restarted by its own `x∞` card, search again. Ask a Mine card for deepslate while standing in a
forest and that is the whole layer under the hill — a measured run spent 52 ms per tick walking it
against a 50 ms budget, found nothing, correctly, and held the client at 9 TPS for as long as the
task ran.

The searches are bounded now, so the work is spread across ticks instead of landing in one. What
is left is that the job is still pointless, and Lune says so: she names the step, how much of each
tick it is costing, and whether it is set to repeat forever, and asks whether that was the intent.
She never cancels it. A step that reported failure would take down the circuit that fed it whenever
no Fail edge is wired, so a card at the end of a chain could kill perfectly good work in front of
it over a wiring mistake — and wiring mistakes are normal. "There is nothing here" is a finding,
not a fault. Turn the warning off under **Warn about slow steps** if you would rather not hear it.

If you want the numbers rather than the verdict, **Debug: profiler** in Config measures where
Lune's own tick time goes and lists the worst sections on the `F6` overlay, worst self-time first.
A single tick over 40 ms also writes a line to the log naming what held it. It is off by default,
because it costs a branch per measured section and the readout only means anything to someone
chasing a stall.

## License

Personal use. Play with it, read it, change it for yourself, keep a private copy, fork it on
GitHub, quote bits of it when you are asking a question about it — all fine. Streaming and video of
your own gameplay is fine too, monetised or not. What you cannot do is redistribute it, put it on a
mod portal or in a modpack, sell it, or ship a derivative. No warranty, and no liability for lost
worlds. See [LICENSE](https://github.com/EtkaPerry/LogicUsingNodeEngine/blob/main/LICENSE) for the
actual terms.

For anything the license does not allow, including redistribution, ask first:
<18562724+EtkaPerry@users.noreply.github.com>. The answer may well be yes.

## Building

```bash
./gradlew build
```

Jars land in `neoforge/build/libs/`, `forge/build/libs/`, and `fabric/build/libs/` — ignore the `-sources` and `-javadoc`
ones.

Run it from the dev environment:

```bash
./gradlew :neoforge:runClient
```

## Adding a command

Register it, and the UI follows — a command declares its own parameters and the node editor renders
them. There are no GUI changes to make.

```java
register(new CommandDef("fish", "Fish", "Cast and reel automatically", List.of(
        new Param.Bool("auto_recast", "Auto recast", "Cast again after each catch", true)
), def -> new FishTask(def.boolValue("auto_recast"))));
```

Then write `FishTask implements Task`.

## Contributing

Contributions are welcome by pull request and are licensed to the project — see
[CONTRIBUTING.md](CONTRIBUTING.md). Bug reports and ideas stay yours.

Issues and pull requests: <https://github.com/EtkaPerry/LogicUsingNodeEngine>

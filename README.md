<h1 align="center">Lune</h1>

<p align="center"><b>L</b>ogic <b>U</b>sing <b>N</b>ode <b>E</b>ngine</p>

<p align="center">
  <img src="https://img.shields.io/badge/Status-Alpha-e05d44?style=flat-square" alt="Alpha">
  <img src="https://img.shields.io/badge/Minecraft-26.1.2%20%7C%2026.2%20%7C%2026.3-44aa44?style=flat-square" alt="Minecraft 26.1.2, 26.2 and 26.3">
  <img src="https://img.shields.io/badge/Loaders-NeoForge%20%7C%20Forge%20%7C%20Fabric-4a6fa5?style=flat-square" alt="NeoForge, Forge, Fabric">
  <img src="https://img.shields.io/badge/Side-Client--only-777777?style=flat-square" alt="Client-side only">
  <img src="https://img.shields.io/badge/License-Personal%20use-c98b2e?style=flat-square" alt="Personal use license">
</p>

---

**Automate Minecraft visually. Connect logic cards like circuits, hit Run, and let your bot handle the grind.**

Lune is a powerful, 100% client-side automation and companion mod. Instead of writing complex scripts, typing console commands, or wrestling with confusing configs, Lune gives you an interactive in-game visual canvas. Drag task cards onto the screen, wire them together with intuitive Success, Failure, and Parallel connections, and watch your character autonomously navigate, fell entire forests, mine deepslate quarries, tend farms, manage inventories, or defend against mobs. A long multi-stage progression route is included too, but it is an experiment rather than a finished speedrun — see the note on tasks 11 to 13.

Designed from the ground up for fairness, safety, and immersion: Lune plays like a real player—using realistic line-of-sight raycasts, humanized camera easing, and intelligent biome scouting rather than omniscient wall-hacks.

---

## Meet Lune

<img src="https://github.com/EtkaPerry/LogicUsingNodeEngine/raw/main/docs/lune-idle.svg" alt="Lune, the floating soul mascot" width="160" align="left" hspace="24">

Floating within your control interface lives **Lune**—a gentle, living soul whose core breathes and shifts with warmth. She isn't just an icon or a cute decoration; she is your autonomous co-pilot and guardian.

As your tasks execute, Lune monitors the world in real time. She visually expresses what is happening—reflecting when the bot is deep in thought, hunting down a path, celebrating a successful harvest, running low on inventory space, missing tools, or sensing imminent danger.

Inside the Task Editor, Lune acts as your intelligent advisor. She watches your circuit layouts and offers proactive, sensible suggestions: reminding you to pack a meal before embarking on a long expedition, recommending a background safety circuit, proposing targets for large mining orders, or highlighting orphaned wires. Every suggestion comes with an instant preview, applies only when you accept it, and can be undone with a single keystroke.

<br clear="left">

---

## Highlights & Features

### Visual Node Canvas
- **Drag-and-Drop Workflow**: Build tasks as intuitive node graphs. Add cards for walking, mining, crafting, fighting, and looting.
- **Natural Logic Flow**: Nodes connect with clear **Success** (green) and **Fail** (red) pins. Branching and looping feel completely natural without learning a scripting language.
- **Concurrent Parallel Circuits**: Use **Always** power nodes to run companion safety circuits (like health monitoring, weapon re-equipping, or perimeter bounds) simultaneously beside your main job.
- **Live Circuit Telemetry**: Watch energized wires light up with moving sparks and active cards glow as your bot steps through its instructions.
- **Blueprints & Clipboard Sharing**: Group complex sub-graphs into reusable blueprints, or export and import entire tasks via JSON through your clipboard.
- **Find Anything (`Ctrl+F`)**: Search the canvas by card name, command id, or anything you typed into a parameter — "diamond" finds the mine card looking for it. Matches light up, everything else dims, and `Enter` walks you from one to the next. Notes and groups are searched too.
- **Sticky Notes (`N`)**: Drop a coloured note anywhere on the board to record *why* a branch is wired the way it is. Notes travel with the task through save, export and import, so the reasoning arrives with the graph instead of staying in your head.
- **Card Groups (`Ctrl+G`)**: Draw a named, coloured frame around a handful of cards. Drag its title bar to move the whole section, and collapse it to a single bar when you are working elsewhere — wires that leave the group still connect to it, so a closed group reads as one card with a lot going on inside.
- **Trace a Cable (middle-click)**: When the wiring gets busy, middle-click a cable to make it shine. It glows, with sparks running from the card it leaves to the card it reaches, both of those cards are ringed, every other cable fades back, and the minimap draws it too. Middle-drag to follow it across the canvas; middle-click it again, click empty canvas, or press `Esc` to let it go.
- **Breakpoints & Step-Through (`F9`)**: Put a breakpoint on a card and the run stops the moment the signal arrives there, before the card does anything. **Step** runs that card and stops at the next one; **Continue** carries on to the next breakpoint. The bot stands still while held rather than drifting on with a walk key down.
- **Copy, Paste, Duplicate (`Ctrl+C` / `Ctrl+V` / `Ctrl+D`)**: Lift a chunk of one task and drop it into another with every wire *between* those cards intact. Press `F` to zoom to what is selected, `Home` to fit the whole task.

### Human-Like Perception (No X-Ray Hacks)
- **True Line-of-Sight Vision**: The bot only interacts with blocks and mobs it can legitimately see. Raycasts and field-of-view cones ensure blocks behind solid walls or thick leaves aren't magically discovered.
- **Smooth, Realistic Camera**: Turns are managed by a specialized physics easing curve (`LookController`) with acceleration and braking. No unnatural robotic snaps.
- **Player-Like Scanning**: Lune glances ahead and performs sweeping head turns when scouting for resources.
- **Biome Intelligence**: The built-in `BiomeScout` analyzes surrounding loaded chunks across 16 headings to seek out wood, water, or stone without blindly swimming across oceans.

### Tactical, Balanced Combat
- **Cooldown-Accurate Strikes**: Swings land exactly as the weapon attack cooldown resets—no chaotic spam clicking.
- **Opening Critical Hits**: Naturally timed jump-critical strikes on the opening engagement.
- **Threat-Responsive Reflexes**: Camera turn limits automatically unlock when dealing with urgent threats such as closing Creepers, flying arrows, or nearby lava pockets.
- **Specialized Mob Tactics**: Dedicated hunt cards with smart behaviors (e.g. building a quick 2-block shelter or trapping Endermen in boats).
- **Strictly Anti-PvP**: Lune's target list is strictly limited to mobs. Other players can never be targeted.

### For When You Are Not Watching
- **Alerts That Reach Another Window**: A sound and an on-screen notice when the run finishes, when a task fails, or the moment the bot dies. Each of those three has a sound of its own, because from another window the sound *is* the message — "it's done" and "it died" should never arrive sounding the same. The `Notify` card puts the same alert behind any condition you like — chest full, three hearts left, a stranger nearby — so being told is a card on the canvas rather than a setting you hope was on. Sounds, volume, and each channel (sound, on-screen, chat) live under **Alerts** on the Config tab.
- **Any Sound in the Game, on a Card**: The `Notify` card's sound is picked from the live registry — every vanilla sound and every sound any installed mod added — through a search box that plays each one as you click it. That turns it from an alarm into an instrument: wire a zombie growl behind a `While` that watches for mobs, a chime when the quarry hits its quota, a different note block pitch for each stage of a long route. Leave the message empty and you get the sound alone, with nothing on screen.
- **Death Points, Remembered**: Every death is written down with its coordinates the instant it happens, whether or not a task was running, and appears under **Death points** on the Waypoints tab. Walk there yourself, copy it into your own waypoints, or wire in the `Recover Death Drop` card to have Lune walk back and sweep up what is still on the ground.

### Interactive In-Game Training Course
- Includes **10 hands-on puzzle challenges** directly within the control panel.
- Learn node mechanics step-by-step: understand power sources, signal flow, loop termination, and life-saving safety circuits.
- Open it from **Menu** at the right end of the panel's tab bar: Lune's corner shows the whole course, what each step is about, and a Continue button for the one that is next.

<details>
<summary><b>Games While Lune Works</b></summary>

- **Redstone Wires**: turn tiles of redstone dust until the block of redstone lights every lamp on the board. It is drawn in the game's own textures and colours, so a resource pack's redstone is the redstone you play with.
- Every board is new. The block of redstone can stand anywhere, some boards are bushy with lamps and some wind through with only a few, and a board you have been dealt is never dealt again, not even turned round or mirrored. Every one has an answer, because each starts as a finished circuit that is then scrambled.
- Four board sizes from 5×5 to 13×11, each with its own best time and a count of how many you have solved. Left-click turns a tile, right-click turns it back, and Shift-click locks one you are sure of.
- The clock only runs while the board is on screen, so looking away to check on the bot never costs you time. Open it from **Menu**, then **Games**.
- **Recipe Riddle**: an item on the left, the game's own crafting table on the right, and twenty stacks below: the recipe's ingredients hidden among decoys. Craft it. Any crafting recipe can come up, a mod's as well as the game's; on a server, the ones your recipe book has unlocked. It handles like any inventory in the game: left-click takes a stack, right-click half, holding the button while you drag across the grid lays one in every square you pass, and Shift-click moves a stack into the grid or a square back out.
- The result square shows whatever your grid would make, so a wrong answer still tells you what it is. Stuck? Place one item, show the shape, or clear half the decoys, and the last square is always yours. Solved riddles are counted, and the ones solved without a hint are counted apart.

</details>

### Accessibility & Modern Comfort
- **Color-Blind Friendly**: One-click toggle for the Okabe-Ito high-contrast color palette, making Success and Failure connections effortless to distinguish.
- **Customizable Themes**: Choose from clean UI themes including Slate, Orange, Blue, Purple, Amber, and Green.
- **HUD Safety Monitor**: Optional in-game overlay showing real-time coordinates, enemy proximity, saturation, equipment durability, light levels, and pathfinding telemetry.
- **Modded Minecraft Ready**: Block, item, and recipe pickers dynamically read the live Minecraft registry—ores, tools, crops, and materials from other mods show up seamlessly.
- **Waystones, JourneyMap & Xaero's Minimap**: The Waypoints tab lists the places those mods already know about, nearest first. Walk to one, or copy it into Lune's own list so any card can use it by name. Nothing is written back into the other mod.
- **Nature's Compass & Explorer's Compass**: `Find Biome` and `Find Structure` hold the compass you crafted and ask it the way its own screen does, then remember the answer for that world. The compass is needed to learn a place, never to go back to it; everything found shows up under **Found** on the Waypoints tab.
- **Sophisticated Backpacks & Traveler's Backpack**: `Deposit to Backpack` and `Take from Backpack` open the backpack you wear or carry and move items with the same shift-click you would use. Only the compartment is touched, never the tool, upgrade or crafting slots.
- **Curios & Trinkets**: `Check Item Count` can count accessory slots too, and an elytra worn on a Curios back slot is treated exactly like one in the chest slot.

---

## Quick Start

1. **Install**: Drop the Lune jar for your loader (**NeoForge**, **Forge**, or **Fabric + Fabric API**) into your `mods` folder.
2. **Open the Panel**: Load into your world and press **`X`**.
3. **Safety First**: On the first launch, review and accept the three quick safety checkboxes (acknowledging server rules, fair use, and safety backups).
4. **Run a Starter Task**: In the **Main** tab, choose a pre-loaded task (start with *1. Chop Wood*) and press **Start**. Open it on the **Task** tab to read the notes that explain every card.
5. **Create Your Own**: Switch to the **Task** tab, drag cards onto the canvas, wire your pins, and create custom automated workflows.

---

## Controls & Keybindings

All keys can be rebound in the standard Minecraft **Controls** screen under the **Lune** category:

| Key | Action | Description |
| :--- | :--- | :--- |
| **`X`** | **Open Control Panel** | Opens the main menu, task canvas, waypoints, and settings |
| **`C`** | **Pause / Resume** | Instantly freezes or resumes the currently running task |
| **`Shift + C`** | **Emergency Stop** | Immediately aborts all bot tasks and hands full control back |
| **`F6`** | **Debug Telemetry** | Toggles the HUD overlay (TPS, path nodes, active tasks, profiler) |
| **`F7` / `F8`** | **Approve / Reject Tactic** | Feedback for the tactical learning engine. Developer builds only; inert in a released jar |

---

<details>
<summary><b>Pre-Loaded Starter Tasks</b></summary>

Lune ships with 15 tasks on a shelf that runs from a two-minute demo to what the engine can be asked to do. The last two need a compass mod and are listed only while it is installed, so a pack without them shows tasks 1 to 13. Every one of them explains itself: open it on the **Task** tab and sticky notes above each section say what the cards under them do, in your language, while framed groups name the sections. Each task reads left to right along one lane, however long it gets.

**Demos** take a few minutes from an empty pack and finish on their own.

| Task | Cards | What It Does |
| :--- | :---: | :--- |
| **1. Chop Wood** | 12 | Punches six logs by hand, makes a wooden axe from them, and chops six more with it. Start here. |
| **2. Stone Tools from Scratch** | 16 | Logs, a wooden pickaxe and twenty stone, then a stone pickaxe, axe and sword. |
| **3. Go Fishing** | 10 | Ten catches at the water you are standing by. Needs a fishing rod. |
| **4. Dig a Tunnel** | 10 | Makes a stone pickaxe if needed, digs 32 blocks the way you face, and walks back out. |

**Chores** run for hours unattended and stop by themselves. Stand beside a chest before pressing Start.

| Task | Cards | What It Does |
| :--- | :---: | :--- |
| **5. Lumber Camp** | 27 | Fells trees, plants a sapling where each one stood, and stores the logs in the chest. Ends after two hours or when the chest is full, then saves and returns to the main menu. |
| **6. Stone Quarry** | 25 | Digs stone and carries the cobblestone to the chest for three hours, or until the chest is full. |
| **7. Homestead, Every Day** | 34 | Harvests and replants by day, keeps a stock of meat, cuts wood, and sleeps or guards the yard at night, for three hours. |
| **8. Smeltery** | 17 | Smelts every raw iron, gold and copper carried, making a furnace and chopping fuel when needed. |
| **9. Night Watch** | 18 | Guards one spot from dark to sunrise, going back to its post after every fight. |

**Expeditions** show what the engine can be asked to do.

| Task | Cards | What It Does |
| :--- | :---: | :--- |
| **10. Get My Stuff Back** | 11 | Walks back to your last death, picks up the drops, puts the armor back on, and comes home. |
| **11. Stone Tools to a Lit Portal** | 56 | *Demonstration graph.* Iron &rarr; diamonds &rarr; obsidian &rarr; a lit Nether portal and ender pearls. See below. |
| **12. New World to Ender Dragon** | 79 | *Demonstration graph.* The whole game, from the first tree to the End. See below. |
| **13. Netherite from the Nether** | 46 | Crosses into the Nether, mines ancient debris, comes home and upgrades a diamond pickaxe. Needs the upgrade template from a bastion. See below. |
| **14. Find a Village** | 13 | Asks the Explorer's Compass for each kind of village in turn, and explores on foot when no compass is carried. Listed only with Explorer's Compass installed. |
| **15. Cherry Grove Timber** | 16 | Finds a cherry grove with Nature's Compass, cuts and replants it, and brings the logs home. Listed only with Nature's Compass installed. |

> **About tasks 11 to 13.** These ship to show the *shape* of a long route — how many cards it takes and how the stages wire together — not as results we can promise. In our own testing the bot has not reached the Nether portal stage of task 12, so every stage past it (fortress, blazes, Ender pearls, the End) is implemented but unproven, and task 13 depends on the same crossing. Run them as experiments and expect them to stop early; tasks 11 and 13 pause the world when they stop, so you can see how far they got.

> **Upgrading from an earlier version?** Your existing tasks are never touched. Press **Restore default tasks** on the Config tab to add the new shelf beside them.

</details>

---

<details>
<summary><b>The Cards Palette</b></summary>

Every card defines a distinct capability that can be combined with others:

| Category | Available Cards & Nodes |
| :--- | :--- |
| **Gathering** | `Mine` (ores/blocks), `Chop Wood` (trees), `Replant Trees` (a sapling back where each felled tree stood), `Harvest` (crops), `Loot` (ground items), `Fish`, `Hunt Sheep` (wool/mutton) |
| **Movement** | `Walk`, `Run`, `Step` (micro-adjustment), `Go to Waypoint`, `Save Waypoint`, `Boat`, `Explore`, `Find Biome` (Nature's Compass), `Find Structure` (Explorer's Compass), `Use Nether Portal` (walk into a lit portal and cross over) |
| **Mining & Building** | `Place Block`, `Bridge` (chasm traversal), `Tunnel` (clearing corridors), `Stripmine` (shaft and branches, mining the ore it opens &mdash; ancient debris in the Nether included), `Build Nether Portal` |
| **Items & Crafting** | `Get Tools` (smart autonomous material gatherer & crafter), `Craft` (recipe & 3x3 grid), `Smelt` (furnaces, ancient debris included), `Upgrade to Netherite` (smithing table; the template and the ingot have to be in the bag), `Deposit` (chests; a full chest fails the card, so a task can end or go elsewhere), `Deposit to Backpack` and `Take from Backpack` (Sophisticated Backpacks, Traveler's Backpack), `Select Item`, `Equip` (wear the best armor carried, or one chosen piece &mdash; accessory slots included where a mod adds them), `Recover Death Drop` (walk back to a death and sweep up the drops) |
| **Combat** | `Kill` (targeted mob), `Hunt Creepers`, `Hunt Skeletons`, `Hunt Endermen`, `Hunt Blazes` |
| **Survival & Safety** | `Eat` (hunger management), `Sleep` (beds), `Self Preservation` (companion combat & hazard avoidance), `Stay Near` (leash radius) |
| **Logic & Flow** | `START`, `End`, `Always` (continuous power), `Pulse` (metronome clock), `While` (condition monitor), `Check Item Count`, `Check Player` (health, hunger, air, XP level, tool durability, free slots, light level, nearest player), `Check Distance`, `Check Time` (day/night phase), `Check Clock` (until 20:00, on the game or system clock), `Check Weather` (clear, raining, thundering), `Check Dimension` (Overworld, Nether, End), `Timer` (pulse delay in game ticks), `Countdown` (20 minutes, 5 hours, 2 days &mdash; real time), `Counter`, `Signal Relay`, `Observer`, `Button`, `Run Task`, `Notify` (any sound in the game, plus an on-screen line) |
| **Grand Missions** | `Complete Game` (experimental multi-stage progression route &mdash; see the note on tasks 11 to 13) |

</details>

---

<details>
<summary><b>Compatible Mods</b></summary>

None of these is required. Lune notices which ones are installed and adds the matching buttons and
cards; without them the panel looks exactly as it always did. Every hook reads the other mod the way
its own screen would, and a mod update that moves a class turns that one feature off with a single
line in the log rather than crashing.

| Mod | What Lune does with it | Where |
| :--- | :--- | :--- |
| **Waystones** | Lists the waystones you have activated, nearest first, with **Go to it** and **Add to waypoints** | Waypoints tab |
| **JourneyMap** | Lists every JourneyMap waypoint, through JourneyMap's own plugin API | Waypoints tab |
| **Xaero's Minimap** (and the Fair-play edition) | Lists every waypoint set of the current world, death points included | Waypoints tab |
| **Nature's Compass** | `Find Biome` holds the compass, asks it for a biome, remembers the answer and walks there | Movement cards |
| **Explorer's Compass** | `Find Structure`, the same for structures | Movement cards |
| **Sophisticated Backpacks** | `Deposit to Backpack` and `Take from Backpack` open the backpack you wear or carry and move items with the same shift-click you would use | Items & Storage cards |
| **Traveler's Backpack** | The same two cards | Items & Storage cards |
| **Curios** | `Check Item Count` can count accessory slots, an elytra on the back slot counts as worn, and `Equip` puts a ring or an amulet on by opening the Curios screen and shift-clicking it across | Logic cards, Self Preservation, `Equip` |
| **Trinkets** (including Trinkets Updated) | The same, on Fabric, except that `Equip` puts a trinket on from the hand the way you would | Logic cards, Self Preservation, `Equip` |

The four cards in that table are in the palette only where their mod is installed, so nothing is
offered that could only fail. A task saved with one still opens, still draws it and still explains
it anywhere, so a task shared out of a modpack is never quietly rewritten on the way in.

Places the compasses find are kept per world and shown under **Found** on the Waypoints tab, so the
compass is needed to learn a place, never to go back to it. Lune never writes into another mod's
data: the only things that cross in the other direction are an item going into a backpack and one
going into an accessory slot, both of them the same action as your own shift-click.

Verified against the 26.1.2 builds of each mod, and compiled for every Minecraft version Lune ships
for. A mod that has not been released for a version simply is not there to hook into.

</details>

---

## Fair Play & Server Safety

- **Client-Side Only**: Lune operates entirely on your client. It requires zero server-side mods or plugins.
- **Respect Server Rules**: Many multiplayer servers strictly forbid automation, macros, or botting. Always check server rules before connecting.
- **Cheating Protections**: The omniscient modes (X-raying blocks the bot cannot see) are not settings at all — they are session-only cheats behind a command, and they are **hard-locked off** unless you are in your own world or hold operator permission on the server. See below.
- **Safety Backups**: Because Lune can dig, place blocks, and traverse terrain, always keep backups of your single-player worlds!

<details>
<summary><b>The Cheat Command</b></summary>

Everything Lune does is deliberately limited to what a player could actually see. The two exceptions
— letting the bot mine or harvest through walls — are cheats, so they are **not on the settings
screen and not in the config file**. They live behind one command, they last only for the world you
are currently in, and they leave nothing on disk:

```
/lune omniscient                  show both modes and whether this world allows them
/lune omniscient mining on        mine any matching block in a loaded chunk, seen or not
/lune omniscient harvest on       harvest any mature crop in a loaded chunk, seen or not
/lune omniscient mining off       back to human-like vision
/lune omniscient off              switch every omniscient mode off
```

**What changed, and why.** These used to be two checkboxes under *Advanced World Access* on the
Config tab, saved into `config/lune.json`. That was the wrong shape for them:

| | Before (a setting) | Now (a cheat) |
| :--- | :--- | :--- |
| Where it lives | `config/lune.json`, on the Config tab | in memory only, behind `/lune omniscient` |
| How long it lasts | forever, across restarts | until you leave the world |
| Who can see the switch | every player, on every server | only whoever types the command |
| Hand-editing the file | sets the flag, which then has to be caught and cleared at runtime | impossible — there is no flag in the file |
| Joining a server with it on | carried over, then cleared | never carried over; the grant belonged to the previous world |

The permission boundary itself is unchanged and still the real protection: `on` is refused outright
unless you are in your own single-player world (including open to LAN) or the server has granted you
vanilla operator permission. That answer comes **from the server**, so no amount of local editing
can fake it — and now there is no local file to edit in the first place. Switching a mode **off** is
always allowed, anywhere.

The command is the only thing Lune adds to chat. Every ordinary setting stays on the panel behind
**`X`**.

</details>

---

## Frequently Asked Questions

<details>
<summary><b>Is Lune a hacked client?</b></summary>
No. Lune is a client-side utility and automation mod built for survival automation, world management, and single-player fun. It contains no PvP features, cannot target other players, and respects vanilla line-of-sight vision. The two omniscient modes that bypass that vision are session-only cheats behind <code>/lune omniscient</code>, refused outright unless you own the world or the server has granted you operator permission — there is no setting or config value that can turn them on.
</details>

<details>
<summary><b>Does Lune need to be installed on the server?</b></summary>
No. Lune is 100% client-side. The server does not need to run Lune, nor does it even know Lune is installed.
</details>

<details>
<summary><b>Does it work with modded blocks, ores, and tools?</b></summary>
Yes! Lune queries Minecraft's live block, item, and entity registries. Ores, tree types, crops, and tools added by other mods appear automatically in search filters and dropdowns.
</details>

<details>
<summary><b>Can I share tasks with friends?</b></summary>
Absolutely. Simply select nodes in the editor and copy them to your clipboard, or use the Export button. Your tasks are exported as clean JSON using standard namespaced IDs (e.g. <code>minecraft:iron_ore</code>) so they work across different mod setups.
</details>

# Development & Contributing

Lune is engineered as a multi-loader project supporting **NeoForge**, **Forge**, and **Fabric** from a single unified codebase. Almost all core intelligence, pathfinding, visual canvas UI, and bot tasks live inside the `common` module.

```
AFKBot/
├── common/             # Platform-independent core logic, node engine, UI & bot AI
├── neoforge/           # NeoForge loader entry points & platform bridges
├── forge/              # Forge loader entry points & platform bridges
├── fabric/             # Fabric loader entry points & platform bridges
└── docs/               # Visual assets, logos, and mascots
```

---

## Building from Source

### Prerequisites
- **Java 25 JDK** (Required for compilation)
- Git

### Build Commands

To build production jars for all supported loaders:

```bash
./gradlew build
```

That targets the default Minecraft version, the `mc` line in `gradle.properties`. One source tree
builds for every supported version; each one has a toolchain file under `gradle/versions/`, and
`-Pmc` picks it:

```bash
./gradlew build -Pmc=26.3
```

In PowerShell, quote it (`./gradlew build "-Pmc=26.3"`) or write `--project-prop mc=26.3`;
PowerShell otherwise cuts the value at the dot and Gradle sees `mc=26`.

Compiled mod jars will be placed in:
- `neoforge/build/libs/`
- `forge/build/libs/` *(Forge has no 26.3 build yet, so that target skips this module)*
- `fabric/build/libs/`

*(You can safely ignore any `-sources.jar` or `-javadoc.jar` files.)*

### Launching the Development Client

Run the client in development mode with hot-reloading:

```bash
# Launch NeoForge dev environment
./gradlew :neoforge:runClient

# Or launch Fabric dev environment
./gradlew :fabric:runClient

# Either one on another Minecraft version
./gradlew :neoforge:runClient -Pmc=26.2
```

---

## Adding a Custom Command or Node

Adding a new card to Lune is completely modular. You register a command definition in `CommandRegistry` with its typed parameters, and the visual node editor builds the configuration GUI automatically:

```java
register(new CommandDef("fish", List.of(
        new Param.Bool("auto_recast", true)
), def -> new FishTask(def.boolValue("auto_recast"))));
```

The card's display name, description, and parameter labels are not passed in code — they are looked up from `en_us.json` under `lune.command.fish.*`, so every string stays translatable.

Then create your task implementation. `Task.tick` is a default method that handles timing and result capture, so your class overrides `onTick`:

```java
public final class FishTask implements Task {
    private final boolean autoRecast;

    public FishTask(boolean autoRecast) {
        this.autoRecast = autoRecast;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        // Your autonomous logic here...
        return TaskStatus.RUNNING;
    }
}
```

---

## Running Tests

To run unit tests across all common algorithms, pathfinders, and translation keys:

```bash
./gradlew :common:test
```

---

## Contributing

Contributions via Pull Requests are warmly welcomed! Please review [CONTRIBUTING.md](CONTRIBUTING.md) and Section 4 of the [LICENSE](LICENSE) before submitting code.

1. **Fork** the repository.
2. **Create a branch** for your feature or bug fix (`git checkout -b feature/amazing-feature`).
3. **Verify tests pass**: Run `./gradlew :common:test`.
4. **Commit your changes** with clear, descriptive commit messages.
5. **Open a Pull Request**.

For bug reports, issues, and feature proposals:
**[GitHub Issue Tracker](https://github.com/EtkaPerry/LogicUsingNodeEngine/issues)**

---

## License

Lune is distributed under a **Personal Use License**. You are free to play with it, modify it for your personal use, fork it on GitHub, and feature it in videos or streams. Free Modrinth modpacks may list Lune — the entry links to the official Modrinth project, so Modrinth serves the author's own file; bundling or re-uploading a copy is not permitted. See [LICENSE](LICENSE) for complete legal terms.

Direct licensing inquiries to: `<18562724+EtkaPerry@users.noreply.github.com>`.

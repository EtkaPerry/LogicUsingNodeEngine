<h1 align="center">Lune</h1>

<p align="center"><b>L</b>ogic <b>U</b>sing <b>N</b>ode <b>E</b>ngine</p>

<p align="center">
  <img src="https://img.shields.io/badge/Status-Alpha-e05d44?style=flat-square" alt="Alpha">
  <img src="https://img.shields.io/badge/Minecraft-26.1.2-44aa44?style=flat-square" alt="Minecraft 26.1.2">
  <img src="https://img.shields.io/badge/Loaders-NeoForge%20%7C%20Forge%20%7C%20Fabric-4a6fa5?style=flat-square" alt="NeoForge, Forge, Fabric">
  <img src="https://img.shields.io/badge/Side-Client--only-777777?style=flat-square" alt="Client-side only">
  <img src="https://img.shields.io/badge/License-Personal%20use-c98b2e?style=flat-square" alt="Personal use license">
</p>

---

**Automate Minecraft visually. Connect logic cards like circuits, hit Run, and let your bot handle the grind.**

Lune is a powerful, 100% client-side automation and companion mod. Instead of writing complex scripts, typing console commands, or wrestling with confusing configs, Lune gives you an interactive in-game visual canvas. Drag task cards onto the screen, wire them together with intuitive Success, Failure, and Parallel connections, and watch your character autonomously navigate, fell entire forests, mine deepslate quarries, tend farms, manage inventories, or defend against mobs. A long multi-stage progression route is included too, but it is an experiment rather than a finished speedrun — see the note on routines 5 and 6.

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
- **Drag-and-Drop Workflow**: Build routines as intuitive node graphs. Add cards for walking, mining, crafting, fighting, and looting.
- **Natural Logic Flow**: Nodes connect with clear **Success** (green) and **Fail** (red) pins. Branching and looping feel completely natural without learning a scripting language.
- **Concurrent Parallel Circuits**: Use **Always** power nodes to run companion safety circuits (like health monitoring, weapon re-equipping, or perimeter bounds) simultaneously beside your main job.
- **Live Circuit Telemetry**: Watch energized wires light up with moving sparks and active cards glow as your bot steps through its instructions.
- **Blueprints & Clipboard Sharing**: Group complex sub-routines into reusable blueprints, or export and import entire tasks via JSON through your clipboard.

### Human-Like Perception (No X-Ray Hacks)
- **True Line-of-Sight Vision**: The bot only interacts with blocks and mobs it can legitimately see. Raycasts and field-of-view cones ensure blocks behind solid walls or thick leaves aren't magically discovered.
- **Smooth, Realistic Camera**: Turns are managed by a specialized physics easing curve (`LookController`) with acceleration and braking. No unnatural robotic snaps.
- **Player-Like Scanning**: Lune glances ahead and performs sweeping head turns when scouting for resources.
- **Biome Intelligence**: The built-in `BiomeScout` analyzes surrounding loaded chunks across 16 headings to seek out wood, water, or stone without blindly swimming across oceans.

### Tactical, Balanced Combat
- **Cooldown-Accurate Strikes**: Swings land exactly as the weapon attack cooldown resets—no chaotic spam clicking.
- **Opening Critical Hits**: Naturally timed jump-critical strikes on the opening engagement.
- **Threat-Responsive Reflexes**: Camera turn limits automatically unlock when dealing with urgent threats such as closing Creepers, flying arrows, or nearby lava pockets.
- **Specialized Mob Tactics**: Dedicated routines with smart behaviors (e.g. building a quick 2-block shelter or trapping Endermen in boats).
- **Strictly Anti-PvP**: Lune's target list is strictly limited to mobs. Other players can never be targeted.

### Interactive In-Game Training Course
- Includes **10 hands-on puzzle challenges** directly within the control panel.
- Learn node mechanics step-by-step: understand power sources, signal flow, loop termination, and life-saving safety circuits.

### Accessibility & Modern Comfort
- **Color-Blind Friendly**: One-click toggle for the Okabe-Ito high-contrast color palette, making Success and Failure connections effortless to distinguish.
- **Customizable Themes**: Choose from clean UI themes including Slate, Orange, Blue, Purple, Amber, and Green.
- **HUD Safety Monitor**: Optional in-game overlay showing real-time coordinates, enemy proximity, saturation, equipment durability, light levels, and pathfinding telemetry.
- **Modded Minecraft Ready**: Block, item, and recipe pickers dynamically read the live Minecraft registry—ores, tools, crops, and materials from other mods show up seamlessly.

---

## Quick Start

1. **Install**: Drop the Lune jar for your loader (**NeoForge**, **Forge**, or **Fabric + Fabric API**) into your `mods` folder.
2. **Open the Panel**: Load into your world and press **`J`**.
3. **Safety First**: On the first launch, review and accept the three quick safety checkboxes (acknowledging server rules, fair use, and safety backups).
4. **Run a Starter Task**: In the **Main** tab, choose a pre-loaded routine (like *Chop 12 Logs*) and press **Run**. Watch the bot get to work!
5. **Create Your Own**: Switch to the **Task** tab, drag cards onto the canvas, wire your pins, and create custom automated workflows.

---

## Controls & Keybindings

All keys can be rebound in the standard Minecraft **Controls** screen under the **Lune** category:

| Key | Action | Description |
| :--- | :--- | :--- |
| **`J`** | **Open Control Panel** | Opens the main menu, task canvas, waypoints, and settings |
| **`K`** | **Pause / Resume** | Instantly freezes or resumes the currently running task |
| **`Shift + K`** | **Emergency Stop** | Immediately aborts all bot routines and hands full control back |
| **`F6`** | **Debug Telemetry** | Toggles the HUD overlay (TPS, path nodes, active tasks, profiler) |
| **`F7` / `F8`** | **Approve / Reject Tactic** | Feedback for the tactical learning engine. Developer builds only; inert in a released jar |

---

## Pre-Loaded Starter Routines

Lune ships with 6 routines demonstrating what the node engine can do. The first four do what they say; routines 5 and 6 are long demonstration graphs — read the note under the table before running them.

| Routine | Complexity | What It Does |
| :--- | :---: | :--- |
| **1. Chop 12 Logs** | 8 Cards | Punches trees by hand, crafts a wooden axe, and harvests wood 33% faster. |
| **2. Wood, Pickaxe, 20 Stone** | 17 Cards | The essential survival kickstart: wooden pickaxe &rarr; stone quarrying &rarr; full stone tool set. |
| **3. Homestead: Farm and Guard** | 22 Cards | Tends crops during daylight and patrols farm perimeters with a **Stay Near** boundary after dusk. |
| **4. Fish Till Dusk, Then Sleep** | 27 Cards | Automated casting and reeling loop governed by the day/night clock, returning to bed at twilight. |
| **5. Stone Tools to a Lit Portal** | 53 Cards | *Demonstration graph.* Wires iron mining &rarr; smelting &rarr; diamond hunting &rarr; obsidian quarrying &rarr; building and lighting a Nether Portal. Does not finish — see below. |
| **6. New World to Ender Dragon** | 79 Cards | *Demonstration graph.* The longest route we have wired, from the first oak tree through to the End. Does not finish — see below. |

> **About routines 5 and 6.** These two ship to show the *shape* of a long route — how many cards it takes and how the stages wire together — not as results we can promise. The `Complete Game` card behind them was written to see how the engine copes with a long multi-stage mission, and that is still what it is for. In our own testing the bot has not reached the Nether portal stage, so every stage past it (fortress, blazes, Ender pearls, the End) is implemented but unproven. Run them as experiments and expect them to stop early.

---

## The Cards Palette

Every card defines a distinct capability that can be combined with others:

| Category | Available Cards & Nodes |
| :--- | :--- |
| **Gathering** | `Mine` (ores/blocks), `Chop Wood` (trees), `Harvest` (crops), `Loot` (ground items), `Fish`, `Hunt Sheep` (wool/mutton) |
| **Movement** | `Walk`, `Run`, `Step` (micro-adjustment), `Go to Waypoint`, `Save Waypoint`, `Boat`, `Explore` |
| **Mining & Building** | `Place Block`, `Bridge` (chasm traversal), `Tunnel` (clearing corridors), `Stripmine`, `Build Nether Portal` |
| **Items & Crafting** | `Get Tools` (smart autonomous material gatherer & crafter), `Craft` (recipe & 3x3 grid), `Smelt` (furnaces), `Deposit` (chests), `Select Item` |
| **Combat** | `Kill` (targeted mob), `Hunt Creepers`, `Hunt Skeletons`, `Hunt Endermen`, `Hunt Blazes` |
| **Survival & Safety** | `Eat` (hunger management), `Sleep` (beds), `Self Preservation` (companion combat & hazard avoidance), `Stay Near` (leash radius) |
| **Logic & Flow** | `START`, `End`, `Always` (continuous power), `Pulse` (metronome clock), `While` (condition monitor), `Check Item Count`, `Check Player`, `Check Distance`, `Check Time`, `Timer`, `Counter`, `Signal Relay`, `Observer`, `Button`, `Run Task` |
| **Grand Missions** | `Complete Game` (experimental multi-stage progression route &mdash; see the note on routines 5 and 6) |

---

## Fair Play & Server Safety

- **Client-Side Only**: Lune operates entirely on your client. It requires zero server-side mods or plugins.
- **Respect Server Rules**: Many multiplayer servers strictly forbid automation, macros, or botting. Always check server rules before connecting.
- **Cheating Protections**: Omniscient mining and harvesting (X-raying unloaded or hidden blocks) are **hard-locked off** on multiplayer servers unless you have operator permissions.
- **Safety Backups**: Because Lune can dig, place blocks, and traverse terrain, always keep backups of your single-player worlds!

---

## Frequently Asked Questions

<details>
<summary><b>Is Lune a hacked client?</b></summary>
No. Lune is a client-side utility and automation mod built for survival automation, world management, and single-player fun. It contains no PvP features, cannot target other players, respects vanilla line-of-sight vision, and restricts omniscient discovery on multiplayer servers.
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

Compiled mod jars will be placed in:
- `neoforge/build/libs/`
- `forge/build/libs/`
- `fabric/build/libs/`

*(You can safely ignore any `-sources.jar` or `-javadoc.jar` files.)*

### Launching the Development Client

Run the client in development mode with hot-reloading:

```bash
# Launch NeoForge dev environment
./gradlew :neoforge:runClient

# Or launch Fabric dev environment
./gradlew :fabric:runClient
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

Lune is distributed under a **Personal Use License**. You are free to play with it, modify it for your personal use, fork it on GitHub, and feature it in videos or streams. The unmodified mod may be hosted on Modrinth and included in free Modrinth modpacks with attribution retained. See [LICENSE](LICENSE) for complete legal terms.

Direct licensing inquiries to: `<18562724+EtkaPerry@users.noreply.github.com>`.

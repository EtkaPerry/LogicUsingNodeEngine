<h1 align="center">Lune</h1>

<p align="center"><b>L</b>ogic <b>U</b>sing <b>N</b>ode <b>E</b>ngine</p>

<p align="center">
  <img src="https://img.shields.io/badge/Status-Alpha-e05d44?style=flat-square" alt="Alpha">
  <img src="https://img.shields.io/badge/Minecraft-26.1.2%20%7C%2026.2%20%7C%2026.3-44aa44?style=flat-square" alt="Minecraft 26.1.2, 26.2 and 26.3">
  <img src="https://img.shields.io/badge/Loaders-NeoForge%20%7C%20Forge%20%7C%20Fabric-4a6fa5?style=flat-square" alt="NeoForge, Forge, Fabric">
  <img src="https://img.shields.io/badge/Side-Client--only-777777?style=flat-square" alt="Client-side only">
  <img src="https://img.shields.io/badge/License-Personal%20use-c98b2e?style=flat-square" alt="Personal use license">
</p>

**Automate Minecraft by wiring cards together. Press Run, and your player does the job.**

Lune is a client-side mod. You build a task on a canvas out of cards — Chop Wood, Mine, Craft, Deposit, Self Preservation — wired by their Success and Fail pins, and Lune carries it out with your own character. She works from what she can see, the way a player does: no X-ray, no snapping camera, and other players are never a target.

<p align="center"><img src="https://github.com/EtkaPerry/LogicUsingNodeEngine/raw/main/docs/Screenshots/Node.png" alt="Starter task 1, Chop Wood, on Lune's canvas" width="900"></p>

## Meet Lune

<img src="https://github.com/EtkaPerry/LogicUsingNodeEngine/raw/main/docs/lune-idle.svg" alt="Lune, the floating soul mascot" width="160" align="left" hspace="24">

Floating within your control interface lives **Lune**—a gentle, living soul whose core breathes and shifts with warmth. She isn't just an icon or a cute decoration; she is your autonomous co-pilot and guardian.

As your tasks execute, Lune monitors the world in real time. She visually expresses what is happening—reflecting when the bot is deep in thought, hunting down a path, celebrating a successful harvest, running low on inventory space, missing tools, or sensing imminent danger.

You don't have to open the panel to hear from her. While a task runs, a small card in the top-right corner of the game shows her face and says what she makes of the moment in the same words as the panel—hungry, blocked, in danger, or simply which step she is on and where she is headed.

Inside the Task Editor, Lune acts as your intelligent advisor. She watches your circuit layouts and offers proactive, sensible suggestions: reminding you to pack a meal before embarking on a long expedition, recommending a background safety circuit, proposing targets for large mining orders, or highlighting orphaned wires. Every suggestion comes with an instant preview, applies only when you accept it, and can be undone with a single keystroke.

<br clear="left">

## What It Does

- **A canvas, not a script.** Drag cards out and wire them; a branch or a loop is just another wire. Guards such as Self Preservation run beside the main job, on an Always card or a card's While pin. Sticky notes, groups, search, copy and paste, and breakpoints are there for when a task grows.
- **Default tasks to learn from.** Lune comes with ready-made tasks that show what the cards can do, and the notes on their canvas guide you through each one. Run one as it is, or use it as a blueprint for a task of your own. The longest, up to the Ender Dragon, are experiments that may stop early. For a small job, **Fast tasks** on the Main tab runs one in a click.
- **Beside you, or in your place.** Switch a task to run beside you and Lune takes the controls only while one of its cards has work in sight — ore you walk past, a Ghast's fireball to bat back — then gives them back. You choose who comes first, and whether she may take the mouse, the keyboard, or both.
- **Plays like a player.** She acts only on blocks and mobs in her line of sight, turns her head smoothly, swings on the weapon's cooldown, swims, jumps gaps, and shuts the doors she opens.
- **Told when it matters.** A sound tells you when a run ends, fails or the player dies, a Notify card plays any sound in the game on a condition you wire up, and every death is kept on the Waypoints tab.
- **At home in a modpack.** Block, item and mob pickers read the game's own lists, so other mods' ores, crops and tools are there without setup. A few mods get more; see *Compatible Mods* below.
- **Share a task as a link.** **Share** turns a task into a link, view only or view and edit, that opens in any browser, and **Import** reads a link back. Nothing is uploaded until you press Create link, anyone with the link can read the task, and a link nobody opens for 30 days is deleted ([policy](https://lunode.etka.co.uk/policy)).
- **Learn it in the game.** A ten-step training course, and two puzzle games for while a task runs, both under **Menu** in the panel.

<p align="center"><img src="https://github.com/EtkaPerry/LogicUsingNodeEngine/raw/main/docs/Screenshots/Working.png" alt="Lune's card in the corner of the screen while a task mines beside the player" width="900"></p>

## Getting Started

1. Download the jar for your loader — NeoForge, Forge, or Fabric with Fabric API — from the [releases](https://github.com/EtkaPerry/LogicUsingNodeEngine/releases) and put it in your `mods` folder. <!-- add the CurseForge page link here -->
2. Join a world and press **`X`**. The first time, read and accept the terms.
3. On the **Main** tab, pick **1. Chop Wood** and press **Start**. Open it on the **Task** tab to see how it is wired; its notes explain every card.
4. Build your own on the **Task** tab, or take the training course under **Menu** first.

| Key | Action |
| :--- | :--- |
| **`X`** | Open the panel |
| **`C`** | Pause or resume; beside you, it hands every key back |
| **`Shift + C`** | Stop everything |
| **`F6`** | Debug overlay |

Rebind them under **Lune** in Controls. A task can also have a key of its own: click the key cap beside its name on the **Task** tab, and that key starts and stops it in game.

<details>
<summary><b>Cards</b></summary>

Grouped the way the palette groups them. Find Biome, Find Structure and the two backpack cards are offered only while their mod is installed.

| Folder | Cards |
| :--- | :--- |
| **General** | Start, End, Always, Pulse, Signal Relay, Observer, Button, Notify |
| **Movement** | Walk, Run, Step, Boat, Explore, Go to Waypoint, Save Waypoint, Find Biome, Find Structure, Find Stronghold, Find Portal Room, Use Nether Portal |
| **Gathering** | Mine, Chop Wood, Replant Trees, Get Tools, Find, Harvest, Hunt Sheep, Collect Dragon Egg |
| **Logic & Conditions** | Check Item Count, Check Player, Check Distance, Check Time, Check Clock, Check Weather, Check Dimension, Timer, Countdown, Counter |
| **Mining & Building** | Tunnel, Stripmine, Bridge, Place Block, Build Nether Portal |
| **Combat** | Kill, Hunt Creepers, Hunt Skeletons, Hunt Endermen, Hunt Blazes |
| **Items & Storage** | Craft, Smelt, Upgrade to Netherite, Deposit, Deposit to Backpack, Take from Backpack, Select from Inventory, Equip, Loot, Recover Death Drop, Fish, Eat, Sleep |
| **Tasks & Missions** | Self Preservation, Stay Near, Run Task, Complete the Game (experimental) |
| **Other** | Follow a Map, Stop the Game, Pause the Game |

</details>

<details>
<summary><b>Compatible Mods</b></summary>

None of these is required. Lune adds their buttons and cards only when they are installed, only reads their lists, and moves items only the way your own shift-click would. A mod update that moves something turns that one feature off, with a line in the log, instead of crashing.

| Mod | What Lune does with it |
| :--- | :--- |
| **Waystones**, **JourneyMap**, **Xaero's Minimap** (and its Fair-Play edition) | Lists their waypoints on the Waypoints tab, nearest first, to walk to or copy into Lune's own |
| **Nature's Compass**, **Explorer's Compass** | **Find Biome** and **Find Structure** ask the compass you carry, and remember the answer for that world |
| **Sophisticated Backpacks**, **Traveler's Backpack** | **Deposit to Backpack** and **Take from Backpack** use the backpack you wear or carry |
| **Curios**, **Trinkets** (or Trinkets Updated) | Accessory slots count for **Check Item Count**, an elytra on the back slot counts as worn, and **Equip** puts accessories on |

</details>

## Fair Play

- Lune runs on your client and needs nothing on the server. Many servers forbid automation, so check their rules first.
- She never targets other players.
- Seeing through walls is a cheat, not a setting. `/lune omniscient mining on` or `/lune omniscient harvest on` lets her mine or harvest blocks she cannot see, until you leave the world, and only in your own world or on a server that allows it: by making you an operator, or through its Lune rules. `/lune omniscient off` turns both off.
- She digs and builds, so keep backups of your worlds.

<details>
<summary><b>For Server Owners</b></summary>

Lune does not need to be on your server, but if you put the same jar in its `mods` folder you decide what Lune may do there. Nobody is kept out for having Lune or not.

| Rule | Decides | Starts at |
| :--- | :--- | :--- |
| `run` | who may run tasks | `operators` |
| `cheats` | who may use `/lune omniscient` | `operators` |

Each rule is `everyone`, `operators` or `nobody`. They live in `config/lune-server.json`, which is picked up a moment after you save it, and operators can change them on the **Server** tab in game, where they also see who has Lune. A world opened to LAN starts at `everyone` for tasks, and its rules never apply to its host.

A server cannot stop a client mod, only say what it allows. Lune 0.9.1.8 and later hold themselves to the answer; an older or modified copy does not.

</details>

## Building from Source

With a Java 25 JDK:

```bash
./gradlew build                  # jars land in <loader>/build/libs/
./gradlew build "-Pmc=26.3"      # another Minecraft version from gradle/versions/
./gradlew :common:test           # run the tests
./gradlew :neoforge:runClient    # play a development build (or :fabric:runClient)
```

Forge has no 26.3 build yet, so that version skips it. Read [CONTRIBUTING.md](CONTRIBUTING.md) and section 4 of the [LICENSE](LICENSE) before opening a pull request, and [TRANSLATING.md](TRANSLATING.md) to translate Lune. Bugs and ideas go to the [issue tracker](https://github.com/EtkaPerry/LogicUsingNodeEngine/issues).

## License

Personal use: play it, change it for yourself, fork it, and show it in videos and streams. Free CurseForge modpacks may list Lune by pointing at its CurseForge project; bundling or re-uploading a copy is not allowed. Full terms are in [LICENSE](LICENSE); licensing questions go to `18562724+EtkaPerry@users.noreply.github.com`.

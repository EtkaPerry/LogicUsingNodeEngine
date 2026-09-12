# Translating Lune

Every line of text in the mod is in one file. Copy it, translate the right-hand
side of each pair, and send it back — that is the whole job.

```
common/src/main/resources/assets/lune/lang/en_us.json
```

**Send it back by opening an issue** with your file attached, and say which
language it is. No pull request needed, no build, no Java.

```json
"lune.gui.tasks.run": "Çalıştır",
```

Leave the left-hand side alone: that is the key the mod asks for. Translate only
what is to the right of the colon.

**A partial translation is a usable one.** English is loaded underneath whatever
language is selected, so anything you have not written yet reads as English
rather than as a raw key. Send what you have; add to it later if you like.

Already shipped: **English** and **Türkçe (Türkiye)**.

## Seeing your work in the game

You do not have to build the mod. Put your file at `config/lune-lang/<code>.json`
and it appears in Lune's language dropdown:

**Config → Lune & Interface → Language**

The choice applies immediately, with no restart. The file is read *on top of* the
shipped language, so one with three lines in it changes three lines and leaves
everything else alone — handy for checking a single screen.

Lune's language is deliberately separate from the game's, so you can play in one
and read her in another.

## Placeholders

`%s` is a value the mod fills in — a number, a block name, another status line.
Keep every one of them, and put them where your language wants them:

```json
"lune.status.bridge.placed_block": "placed block %s/%s",
"lune.status.mine.mine": "need a better tool to mine %s",
```

If your language needs a different word order, number them: `%1$s` is the first
value, `%2$s` the second, and so on.

```json
"lune.goal.within": "within %s of %s, %s, %s",
"lune.goal.within": "%2$s, %3$s, %4$s noktasının %1$s blok yakını",
```

`%%` means a literal percent sign. Dropping a placeholder loses the value rather
than breaking the screen, but it is still worth fixing.

## Lune's dialogue

Her speech is stored in **banks** — numbered keys read from `.1` upwards until
one is missing:

```json
"lune.mascot.idle.morning.1": "Good morning. What are we going to do now?",
"lune.mascot.idle.morning.2": "I'm here with you. Shall we make a little plan?",
"lune.mascot.idle.morning.3": "New day, new little adventure.",
"lune.mascot.idle.morning.4": "Take your time waking up."
```

The count is discovered, not declared, so **your language may have a different
number of lines than English does.** Add `.5` to give her a fifth way of greeting
a morning; delete `.3` and `.4` to leave her with two. Keep the numbering
contiguous — the scan stops at the first gap.

## Use the game's own words

A player reads Lune's status line and the item in their hand in the same second.
When the game says `Blaze Rod` and Lune says something else, they have to work
out that those are the same object.

So for anything Minecraft names — blocks, items, mobs, biomes, effects — **use
the wording from Minecraft's own translation**, not your own. If your language
leaves a name in English, as Turkish does with *Blaze* and *Creeper*, leave it in
English too. A Blaze is a mob, not a flame.

## What not to translate

- **The terms and the licence screen.** It is the one thing a player is asked to
  agree to, and an agreement means what its author wrote. It stays in English.
- **Anything you cannot find in the file.** Most block, item and mob names are
  asked of the game directly and are already correct in your language. A handful
  of lines read like `"@item.minecraft.diamond"` — those say "ask the game", so
  leave them out of your file entirely.
- **Lines already in English on purpose.** `Lune`, `Minecraft` and the mod's own
  name are not translated.

## Two things with a catch

**`lune.task.seeded.*`** — the six starter job titles — have a **32-character
limit**, because they appear in the rename box and a longer one is cut off
mid-word.

**`lune.reason.*`** is never a whole line. It is dropped *into* one, so
`lune.reason.took_too_long` lands inside
`lune.status.loot.drop_trying_next_one`. Read the two together and use numbered
placeholders if your language wants the reason somewhere else in the sentence.

## What the keys are grouped into

| Prefix | What it covers |
| --- | --- |
| `lune.status.*` | The live one-line detail a running task reports |
| `lune.command.*` | The Tasks palette: card names, descriptions, parameter labels and tooltips |
| `lune.mascot.*` | Everything Lune says, including the numbered dialogue banks |
| `lune.gui.*` | Buttons, headings, tooltips and panel text |
| `lune.card.*` | The plain-English description on the back of each blueprint card |
| `lune.suggestion.*` | The wording of Lune's suggestions and their previews |
| `lune.audit.*` | Wiring problems reported on the blueprint |
| `lune.training.*` | The training course's lesson titles, briefs and hints |
| `lune.task.*` | Running task names, as shown on the Main tab and in the queue |
| `lune.task.seeded.*` | The titles of the six starter jobs a new profile receives |
| `lune.param.*` | Wording shared by a parameter that appears on several cards |
| `lune.choice.*` | What dropdown options are *called* |
| `lune.unit.*` | What a progress bar counts: "3 / 10 **blocks**" |
| `lune.reason.*` | Why a task gave up on something, filled into a status line |
| `lune.chat.*` | What Lune says in chat rather than on a panel |
| `lune.compass.*`, `lune.scout.*`, `lune.engine.*` | Directions, scouting reasons, engine messages |

Developers: the tooling and the checks behind all this live with the contributor notes, alongside
the rest of the house rules for text.

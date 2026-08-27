# Contributing to Lune

Pull requests and issues are welcome. Fork the repo, make your change, open a PR.

## Before you open a pull request

Read section 4 of the [LICENSE](LICENSE). The short version:

- Code, assets, documentation and routines you submit are licensed to the Author —
  perpetual, worldwide, irrevocable, royalty-free, exclusive and sublicensable — and
  you agree to sign an assignment if one is ever needed.
- The contribution has to be your own work, and yours to give.
- You get no payment or guaranteed credit for it, though credit is often given.
- Merged contributions become part of Lune and are covered by the same license.

Opening a pull request is how you accept that. The PR template has a checkbox for it,
and a PR that leaves it unticked will not be merged.

**Bug reports, feature requests and ideas are different.** Section 4 does not touch
them. File them freely — no rights change hands, and Lune may act on them.

## What makes a PR easy to merge

- One change per PR. A pathfinder fix and a UI tweak are two PRs.
- Match the code around you: same naming, same comment density, same idiom.
- Comments explain *why*, not *what*. The code already says what.
- Run the tests: `./gradlew :common:test`.
- If the change touches behaviour you can see in game, say what you saw when you ran it.

## What is unlikely to be merged

- Anything that makes Lune easier to use against a server that forbids automation.
- Reformatting, renaming or restructuring without a behaviour change behind it.
- New dependencies. The common module compiles against vanilla and Mixin, and that is
  the whole list.

## Questions

Open an issue, or mail <18562724+EtkaPerry@users.noreply.github.com>.

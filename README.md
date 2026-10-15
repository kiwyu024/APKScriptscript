# Tap Assist (TerraMine Gold Rush test bot)

Android auto-player for the **TerraMine → Gold Rush** pipe puzzle. It uses an
AccessibilityService that takes screenshots and taps, with a floating ▶/⚙ panel to start and stop it.

What it does, each tick (~0.7 s):

| Screen | Action |
|---|---|
| Puzzle board | Finds the board by its yellow frame, works out the grid size from the seams between tiles (4×4, 5×5, 6×6 or 7×7), reads every tile's road openings, locates the leprechaun and the pot of gold, finds the rotations that connect them **with the fewest taps**, and taps the tiles (a tap turns a tile clockwise). |
| Result screen | Taps **Play Again** or **Watch Ad → Play Level N**. It never taps Exit. |
| Ad | **Off by default** ("Watch & close ads automatically", intended for builds serving AdMob test ads). With it off the bot stops at Watch Ad screens. When on: waits until a close **X** appears in either top corner (e.g. AdMob's "Reward granted ✕") and taps it. If no X shows within 90 s it tries the corners, then Back. If a tap opens the Play Store or a browser, it presses Back. |

The vision was tuned on 1080×2340 screen recordings from a Galaxy S25+ (levels 1 and 50–53). The leprechaun and pot
are located anew on every screenshot, so random spawns are handled. If the floating panel sits over the board, it is
made invisible for each capture.
All measurements are proportional to the screen and board size.

## Install

1. Copy `dist/TapAssist-0.8.3.5.apk` to the phone and install it (allow installs from that app when asked).
2. Open **Tap Assist** → **App info** → ⋮ → **Allow restricted settings**. Android 13+ requires this before a
   sideloaded app can use accessibility.
3. **Accessibility settings** → Installed apps → **Tap Assist** → On.
4. Open TerraMine → Gold Rush and press ▶ on the floating panel (bottom-right, over the game's Settings tab).

⚙ → **Check board now** shows what the bot reads: the tile grid (`LEP`/`POT`/openings), the tap count
for the road, the replay button position and the X detector score. **Save debug snapshot**, or the
"Save debug images" setting, writes raw and annotated screenshots to `Pictures/TapAssist`.

## Resource collector (Mode: Collect resources)

A second script that walks your Properties list and collects resources from every
mine that still has an attempt left.

1. Open the floating panel menu (⚙) while stopped → tap **Mode** until it says
   **Collect resources**.
2. In the game go to **Profile → Properties** and press ▶.

What it does:
- Taps each property card once per pass (remembered by its coordinates).
- On the mine screen it reads the badge: **1/3 → enters**, **0/3 → Back** and tries the next one.
- In the activity it taps the action button (PAN FOR GOLD / PULL LEVER / etc., or the
  green button / the picture when no text is readable), waits while it is busy, and taps
  **COLLECT REWARDS** (never Watch Ad) on the rewards screen.
- Scrolls the list, taps **Load 20 more** when it reaches the bottom, and when nothing
  new is left it waits *Collect: minutes between passes* (main screen) and starts again
  from the top.
- If it lands on a screen it doesn't recognise it presses Back / taps Profile to get back
  to the Properties list.

Screens are recognised from the game's accessibility text; if the game doesn't expose
any, it falls back to on-device OCR (ML Kit). ⚙ → **Read screen text** shows which one is
working on your phone.

## Ads with an arrow before the X

Some ad formats show a round white **->** (next/skip) button in a top corner first, and only then the X. When no X is
visible, the bot looks for a white disc with a dark symbol in either top corner and taps it (at most 3 times per ad).
It then closes the ad with the X as usual.

## Getting back into the mine

If the game drops back to the Gold Mine screen, the bot recovers on its own. It taps **GOT IT!** on the "Name Your
Mine!" dialog, swipes down until **ENTER MINE** shows, and taps it. It uses the game's button labels when they're
readable, and otherwise the colours measured from screenshots: the brown header, the brown ENTER MINE button, and the
grey dialog with teal GOT IT!.

## Speed

Settings → **Speed**, or the **1x** button on the floating panel (cycles 1x → 1.25x → 1.5x → 1.75x → 2x → 3x, and works while
running), divides every pause and tap gap by the multiplier. It never goes below what the game needs. Measured on
the 120 fps recordings, a tile turn animates for about 120–175 ms, so the limits are: 200 ms between taps on the
same tile, 90 ms when moving to another tile, 400 ms before re-reading the board, and about 3 screen checks a
second (Android's screenshot limit). Mistake rates are unchanged. Breaks between rounds keep their length.

## Mistakes (on by default, independent of Humanize)

Each puzzle is either **clean** (a few wrong taps) or **messy** (more). The clean chance falls with board size:

| Board | Clean chance | Wrong taps per round (most likely → rare) |
|---|---|---|
| 4×4 | 97% | mostly **1** (0 29%, 1 44%, 2 24%, 3 2.3%, 4 0.6%, 5 0.1%) |
| 5×5 | 80% | mostly **2–3** (64%), 1 16%, then 4 → 8 getting rarer (8: 0.6%) |
| 6×6 | 60% | mostly **2–3** (48%), 1 12%, then 4 → 12 getting rarer (12: 0.7%) |
| 7×7 | 10% | mostly **5–8** (55%), 1–3 10%, 4 8%, then 9 → 15 getting rarer (15: 0.8%) |

The clean chance (wrong taps within 0–2 on 4×4, 1–3 on bigger boards) is nudged down for boards that need more turns
than usual for their size, and up for easier ones (only slightly near 100%). Wrong taps go on road tiles that aren't
on the route, on grass, or bump a correct tile and fix it. On top of that, the correct taps **over-rotate** at a rate
picked per round (0–10%, averaging 5%): the right tile goes one step too far, then on round to where it should be
(+4 taps, or +2 for a straight piece, which repeats every two turns). Plans are simulated so the road connects on the
final tap. Retries after a misread are clean. The clean chances and over-rotation rate are in Settings → Mistakes.

**Straight-line maps** (leprechaun and gold in the same row or column with only straight pieces between) never get a
wrong tile. 95% of those rounds are spot on, and 5% over-rotate one tile and fix it. A map that needs just one tile
turned is always spot on, because that tap finishes it. Plans are simulated so the road connects on the final tap. Retries after a misread are clean.
All the numbers are in Settings → Mistakes.

## Humanize mode (anti-cheat testing)

See [docs/ANTI_CHEAT_NOTES.md](docs/ANTI_CHEAT_NOTES.md). It lists the signals an anti-cheat for this game should
look at, what the bot does about each one, and what it can't hide (the strongest checks).

Settings → **Humanize taps** layers randomness at three levels:

- **Session:** a habitual thumb offset and spread, base speed, typical press length, and rounds until a break.
- **Start habit:** the first tap is always the first tile needing a turn next to the leprechaun (65% of rounds)
  or next to the gold (34%). The other 1% open with a stray tap on another block, which counts as a bad tap.
  From there it works along the road from that end. Bad taps never come before the first tap.
- **Round:** mood (speed factor), slowly growing fatigue (resets after breaks), **1–15 bad taps** (weighted
  towards fewer; 1% of rounds perfect), 0–2 tiles left half-turned and finished later, and a rare 5–25 s distraction.
- **Tap:** position around the thumb offset (4% sloppy), log-normal press length, finger slide, faster repeat
  taps on one tile, Fitts-style travel time between tiles, hesitations, and a "noticing" pause before fixing a
  mistake. Limits are soft, so values never pile up on a bound.

Bad taps are over-rotations and accidental double taps (then going round again, +4), taps on road tiles off the
route (+1–3), bumping a correct tile and fixing it (+4), and taps on grass (+1). They are placed near the tiles
being worked on. When the game's "N left" counter is readable through accessibility, bad taps stay within it
(minus a margin). Each round's plan is simulated tap by tap, and the road only connects on the very last tap.
The bot also takes breaks every 8–25 rounds (45 s–5 min), and its screen polling and Play Again reaction are jittered.

With humanize off, taps are exact tile centres, 40 ms long, at fixed intervals, in row order, with no mistakes.
That's the "obvious bot" baseline.

**Log every tap** writes `Download/TapAssist/taps_<time>.csv` when the bot stops
(`epoch_ms,x,y,press_ms,drift_x,drift_y,kind,detail`; kind is `tile`, `overshoot`, `bounce`,
`correction`, `wrong_tile`, `grass`, `revisit`, `replay` or `ad_close`), so you can line it up with your anti-cheat telemetry.

## Build

```
./gradlew assembleRelease   # needs Android SDK 35; output: app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key (sideload only).

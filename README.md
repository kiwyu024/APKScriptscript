# TerraMine Bot

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

1. Copy `dist/TerraMineBot-0.7.apk` to the phone and install it (allow installs from that app when asked).
2. Open **TerraMine Bot** → **App info** → ⋮ → **Allow restricted settings**. Android 13+ requires this before a
   sideloaded app can use accessibility.
3. **Accessibility settings** → Installed apps → **TerraMine Bot** → On.
4. Open TerraMine → Gold Rush and press ▶ on the floating panel (bottom-right, over the game's Settings tab).

⚙ → **Check board now** shows what the bot reads: the tile grid (`LEP`/`POT`/openings), the tap count
for the road, the replay button position and the X detector score. **Save debug snapshot**, or the
"Save debug images" setting, writes raw and annotated screenshots to `Pictures/TerraMineBot`.

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

**Log every tap** writes `Download/TerraMineBot/taps_<time>.csv` when the bot stops
(`epoch_ms,x,y,press_ms,drift_x,drift_y,kind,detail`; kind is `tile`, `overshoot`, `bounce`,
`correction`, `wrong_tile`, `grass`, `revisit`, `replay` or `ad_close`), so you can line it up with your anti-cheat telemetry.

## Build

```
./gradlew assembleRelease   # needs Android SDK 35; output: app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key (sideload only).

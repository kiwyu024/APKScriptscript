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

1. Copy `dist/TerraMineBot-0.4.apk` to the phone and install it (allow installs from that app when asked).
2. Open **TerraMine Bot** → **App info** → ⋮ → **Allow restricted settings**. Android 13+ requires this before a
   sideloaded app can use accessibility.
3. **Accessibility settings** → Installed apps → **TerraMine Bot** → On.
4. Open TerraMine → Gold Rush and press ▶ on the floating panel (bottom-right, over the game's Settings tab).

⚙ → **Check board now** shows what the bot reads: the tile grid (`LEP`/`POT`/openings), the tap count
for the road, the replay button position and the X detector score. **Save debug snapshot**, or the
"Save debug images" setting, writes raw and annotated screenshots to `Pictures/TerraMineBot`.

## Humanize mode (anti-cheat testing)

Settings → **Humanize taps** randomises:

- the tap position inside each tile (normal spread, clamped; default up to 55% of half a tile from centre)
- press length (45–130 ms) and a small finger slide while pressed (±3 px)
- gaps between taps (log-normal around "Delay between taps") plus extra time when moving to another tile
- a "reading the board" pause before the first tap (0.7–2.6 s) and a reaction pause before Play Again
- tile order (usually along the road from the leprechaun, sometimes from the pot, sometimes shuffled, with
  neighbouring tiles occasionally swapped)
- optional deliberate mistakes (a full extra turn on a tile; costs 4 taps)

With humanize off, taps are exact tile centres, 40 ms long, at fixed intervals, in row order. This gives a
clean "obvious bot" baseline to compare against.

**Log every tap** writes `Download/TerraMineBot/taps_<time>.csv` when the bot stops
(`epoch_ms,x,y,press_ms,drift_x,drift_y,kind,detail`), so you can line it up with your anti-cheat telemetry.

## Build

```
./gradlew assembleRelease   # needs Android SDK 35; output: app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key (sideload only).

# TerraMine Bot

Android auto-player for the **TerraMine → Gold Rush** pipe puzzle. It uses an
AccessibilityService that takes screenshots and taps, with a floating ▶/⚙ panel to start and stop it.

What it does, each tick (~0.7 s):

| Screen | Action |
|---|---|
| Puzzle board | Finds the board by its yellow frame, reads every tile's road openings, locates the leprechaun and the pot of gold, finds the rotations that connect them **with the fewest taps**, and taps the tiles (a tap turns a tile clockwise). |
| Result screen | Taps **Play Again** or **Watch Ad → Play Level N**. It never taps Exit. |
| Ad | Waits until a close **X** appears in either top corner (e.g. AdMob's "Reward granted ✕") and taps it. If no X shows within 90 s it tries the corners, then Back. If a tap opens the Play Store or a browser, it presses Back. |

The vision was tuned on 1080×2340 screen recordings from a Galaxy S25+ (levels 50–53).
All measurements are proportional to the screen and board size.

## Install

1. Copy `dist/TerraMineBot-0.1.apk` to the phone and install it (allow installs from that app when asked).
2. Open **TerraMine Bot** → **App info** → ⋮ → **Allow restricted settings**. Android 13+ requires this before a
   sideloaded app can use accessibility.
3. **Accessibility settings** → Installed apps → **TerraMine Bot** → On.
4. Open TerraMine → Gold Rush and press ▶ on the floating panel (bottom-right, over the game's Settings tab).

⚙ → **Check board now** shows what the bot reads: the tile grid (`LEP`/`POT`/openings), the tap count
for the road, the replay button position and the X detector score. **Save debug snapshot**, or the
"Save debug images" setting, writes raw and annotated screenshots to `Pictures/TerraMineBot`.

## Build

```
./gradlew assembleRelease   # needs Android SDK 35; output: app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key (sideload only).

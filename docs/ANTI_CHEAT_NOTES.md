# Gold Rush anti-cheat: signals and how the test bot answers them

This document has two halves. The first is how I would detect a bot in TerraMine Gold Rush. The second is what
the bot in this repo does to look natural against each check. The **residual tells** column lists what it can't
hide. Those are the checks to build first.

## Where to collect data

Log every touch (client) and every solved round (server):

- **Per touch:** timestamp (ms), x/y relative to the tile hit, tile row/col, whether it rotated something,
  touch-down/up times. Also the raw touch fields: pressure/force, size/radius, tool type, device id, and for a
  WebView `Touch.force` and `radiusX/radiusY`.
- **Per round:** board size, leprechaun/pot cells, minimum taps (solve it server-side), taps used, time from
  board shown to first tap, total time, order tiles were touched in, result.
- **Per session:** rounds, gaps between rounds, Play Again reaction time, time of day, device/app info.

## Signals → bot counter → residual tells

| # | Signal (what an anti-cheat looks at) | What the humanized bot does | Residual tells |
|---|---|---|---|
| 1 | **Tap position**: always the exact tile centre, or a tight/identical spread every session | Normal spread around a per-session thumb bias (random direction, 4–18% of half a tile). Spread size varies by session. About 4% of taps are sloppy, out to 80% of the way to the edge. | The spread is a clean 2-D Gaussian. Real thumbs are skewed and depend on where the tile is on screen (reach). |
| 2 | **Press length / finger slide**: constant press time, zero movement | Log-normal press length with a per-session median, scaled by tempo. 0–N px slide in a random direction. | **Pressure and contact size can't be set** by an AccessibilityService; they come out at fixed values. Log them. |
| 3 | **Tap rhythm**: identical gaps, or a narrow distribution | Log-normal gaps. Repeat taps on one tile are faster than moving to another. Travel time follows Fitts' law (longer for farther tiles). There are hesitations mid-solve. | Gaps don't respond to the rotation animation the way a person waiting for it might. |
| 4 | **Time to first tap**: instant, or unrelated to board difficulty | "Reading" pause that grows with the number of taps needed, × session tempo × round mood × noise. | The bot's screenshot loop sets a floor, roughly one poll (0.5–1 s, jittered). |
| 5 | **Optimality**: taps used = minimum taps every round | 1–15 bad taps per round (skewed toward fewer), plus 0–2 "come back later" revisits. 1% of rounds are perfect. | **The underlying route is always the optimal one.** People often try a longer or wrong route first, then change their mind. Compare *which tiles* were touched with the optimal set. |
| 6 | **Mistake realism**: errors random across the board, or perfectly spaced | Over-rotations (go round again), double-tap bounces (2nd tap 55–120 ms later), taps on tiles near the road, bumping a correct tile and fixing it, taps on grass. Wrong tiles are picked by distance to the road, and mistakes happen next to the tile being worked on. | The bot never misreads a tile. Its mistakes are deliberate, so it never "fixes" a tile that wasn't broken in a way that suggests misperception. |
| 7 | **Order / opening move**: row-by-row scanning, or a first tap that isn't where a person would start | Start habit: the first tap is the first tile needing a turn next to the leprechaun (65%) or next to the gold (34%). 1% of rounds open with a stray tap elsewhere, which counts as a bad tap. It then works along the road from that end, occasionally swapping neighbouring tiles. | The opening split is a fixed ratio across sessions. A real player's habit drifts (and may depend on which side the leprechaun is on). The order always covers exactly the optimal tiles plus mistakes. |
| 8 | **Finishing**: the road completes before the "last" planned tap | Plans are simulated so the road connects on the final tap. | – |
| 9 | **Consistency across rounds**: the same stats every round | Per-round "mood" speed factor. Fatigue slowly slows it down over a session and resets after a break. | The drift is smooth. Real fatigue is noisier and tied to time of day. |
| 10 | **Session shape**: plays non-stop, rounds/hour constant, Play Again pressed at a fixed delay | Breaks every 8–25 rounds (45 s–5 min). Rare 5–25 s distractions mid-round. Log-normal Play Again reaction. Jittered poll interval. | No long off-hours gaps unless you stop it. A 24-hour activity histogram still exposes it. |
| 11 | **Tap budget**: stays far from the "N left" limit | Reads "N left" through accessibility when the game exposes it and keeps a margin. | – |
| 12 | **Automation present on the device** | Generic name ("Tap Assist", `com.kiwyu.tapassist`). The service is not hidden, and its description truthfully says it automates play. | **Strongest check, if it goes by capabilities rather than names.** `AccessibilityManager.getEnabledAccessibilityServiceList()` lists enabled services. A service with both `canPerformGestures` and `canTakeScreenshot` that isn't a known assistive tool is a direct flag, whatever it's called. A name or package blocklist is easy to dodge by renaming. Apps can also read other installed APKs (`ApplicationInfo.sourceDir`) and scan them for game-specific strings; this one still contains "leprechaun"/"pot". |
| 13 | **Injected input** | Not hidden. | Gestures injected by an accessibility service may differ from real touches in pressure, size, device id or flags. Compare a real tap and a bot tap in your own logging to see which fields differ on your Android version. |
| 14 | **Overlay**: a window over the game | The panel is an accessibility overlay. | Apps can check for obscured touches with `FLAG_WINDOW_IS_OBSCURED` / `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` on MotionEvents. |

## Suggested detection approach

1. **Hard checks (cheap, reliable):** #12, #13 and #14. Treat them as strong evidence rather than proof, since
   legitimate accessibility tools exist.
2. **Per-round features to the server:** taps vs. minimum, time to first tap, gap distribution (median, IQR,
   coefficient of variation), tap offset from tile centre (mean, covariance), share of taps on optimal tiles,
   and whether wrong taps are near the road.
3. **Per-player model:** compare a player's features with the population and with their own history. Bots
   are often *too* average, and too stable across days.
4. **Run this bot against it:** with humanize off (obvious bot), then on with default settings, then with the
   ranges pushed. The tap log CSV (`Download/TapAssist`) gives the ground truth for each tap.

## Detecting ad skipping (rewarded ads)

The Ad Tester app, and Tap Assist with "Watch & close ads" on, act on **Google test ads only**. Each ad gets one
viewer type:

- **watch:** waits for the X, then reacts in about 0.3–6 s. Sometimes it's slow to notice; 7% of the time it
  misses the X and taps again.
- **impatient:** 1–3 early attempts (taps where the X will be, or Back), then closes fast once the X shows.
- **skipper:** Back once or twice, then Home, then returns after 2–10 s. In a "close video?" dialog it picks
  close 80% of the time.

Each ad is logged: start, viewer type, when the X appeared, each action, and how it ended.

What the game can observe, using the Google Mobile Ads SDK callbacks:

| Signal | How |
|---|---|
| Ad dismissed without a reward | `FullScreenContentCallback.onAdDismissedFullScreenContent` fires with no `OnUserEarnedRewardListener.onUserEarnedReward` before it. |
| Very short view | Time from `onAdShowedFullScreenContent` to dismissal. |
| Left the app during the ad | Your Activity/Application lifecycle (`onStop`/`onStart`, or `ProcessLifecycleOwner`) while the ad is showing. |
| Repeated Back presses | Not visible to the game while the ad has focus. Infer it from the close-confirmation outcome or a missing reward. |
| Reward-farming patterns | Rewards per hour, ads started vs rewards earned, and identical time-to-close across many ads (bots are too consistent). |

Grant rewards server-side where you can (AdMob server-side verification callbacks), so a client can't claim a
reward it didn't earn.

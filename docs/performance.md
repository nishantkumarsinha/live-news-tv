# Performance notes

Measured on a Xiaomi Mi Box 4 (1,987 MB RAM, Android TV) on 26 Oct 2026, 14:19 to 16:25 IST.
This is one device and one run, so read it as a guide and not as a benchmark.

## How it was measured

An automated run over adb reorders the channel rows by long-pressing OK, moving a row and
confirming, 500 times in total. About every 30 seconds it samples `dumpsys meminfo` for the app.
After 400 moves it opens a channel (YouTube TV comes to the front), goes back, and carries on
for 100 more moves. Rounds where the test could not find the focused tile were skipped and
retried, so the 500 moves are completed moves.

## Results

| Measure | Result |
|---|---|
| APK size | 2.8 MB |
| Third-party libraries | 0 |
| App data on the TV | about 340 KB |
| Memory at fresh start (PSS) | 33 MB |
| Memory during 500 moves (PSS) | median 60 MB, peak 72 MB |
| Memory trend | flat: median 60.4 MB for moves 100 to 200, 61.0 MB for moves 400 to 500 |
| After opening YouTube TV and returning | about 58 MB, inside the same band |
| TV memory in use | 56 to 58 percent, lowest free RAM 830 MB of 1,987 MB |
| Java heap | 7 to 13 MB |
| Native memory | 14 MB at start, median 36 MB during moves |
| Views on screen | 528 at start, up to about 1,590 while rows are being moved |

## What the numbers say

- No sign of a leak. Memory rises in the first 50 moves and then stays flat for the other 450.
- Memory roughly doubles after the first reorders and the view count rises with it, in steps of
  the original 528 (about 1,050 and 1,580). That suggests extra copies of the tile views are
  held while rows are moved. The growth is bounded.
- Opening a channel hands over to YouTube TV, and after returning the app was back in the same band.

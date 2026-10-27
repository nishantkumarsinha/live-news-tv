# Install and run

For anyone building this app on their own computer and installing it on their own Android TV.
The app is installed by sideloading over adb. It is not on the Play Store.

## What you need

- A computer with Git, and either Android Studio or a JDK plus the Android SDK (platform 37)
- An Android TV or Google TV on Android 9 or newer (min SDK 28), with the YouTube TV app
  installed. The app opens each live stream there.
- The TV and the computer on the same Wi-Fi network
- Internet on the first build, because Gradle downloads its JDK (toolchain 25) and libraries

## 1. Turn on network debugging on the TV

Open Settings, then Device Preferences, then About, and press Build several times to unlock
Developer options. Then turn on USB debugging and, where your TV offers it, network or wireless
debugging. Note the TV address from Settings, then Network. Menu names vary by make.

## 2. Get the code

```
git clone https://github.com/nishantkumarsinha/live-news-tv.git
cd live-news-tv
```

## 3. Choose where the channel list comes from

**Route A, the default: use the author's feed.** Nothing to change. The app downloads the
channel list and logos from a public address (`livenewsindia.nishvik.com`). No key is needed.
This feed is run by the author on a best-effort basis and can change or stop. If it does, the
app keeps showing the last list it downloaded and then shows a "List not updated" warning.

**Route B, your own list.** Change `FEED_URL` in `app/build.gradle.kts`, or set it to an empty
string and push the files with adb. [feed.md](feed.md) explains both, including how to host the
list on your own Cloudflare account.

## 4. Build and install

```
./gradlew assembleProdDebug          (on Windows: gradlew.bat assembleProdDebug)
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/prod/debug/app-prod-debug.apk
```

Accept the debugging prompt on the TV the first time. The `prod` flavor reads the author's
`/prod` list. The `stage` flavor is identical except that it reads `/test`, which is where
the author tries changes first, so use `prod` unless you know you want `stage`.

In Android Studio: open this folder, choose the `prodDebug` build variant, pick the TV as the
target and press Run.

## 5. First start

Open Live News (India) from the TV launcher. The list downloads within a few seconds of the
first start. After that the app checks again about every 5 minutes while it is on screen.
Press OK on a logo to open that channel's live stream in the YouTube TV app. Hold OK on a row to
move it, and use the pill at the top right to change the theme.

## Update or remove

- Update: pull the latest code, build again and run the same `adb install -r` command.
- Remove: `adb uninstall com.nishvik.livenews`

## Troubleshooting

| Problem | Fix |
|---|---|
| `adb: failed to connect` | Turn on network debugging, check the TV address, and accept the prompt on the TV. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | An earlier copy was signed with another debug key. Run the uninstall command, then install again. Saved row order is lost. |
| Build asks for SDK platform 37 | Install it in Android Studio under SDK Manager. |
| Message about YouTube TV missing | Install the YouTube TV app from the TV's app store. |
| A channel shows "Not live" | It has no live stream at the moment. This is normal. |
| "List unavailable" or "not updated" | Check the TV's internet. If you use your own feed, check that `FEED_URL` is right and that the Worker returns the file. |

The builds are debug-signed, which is fine for sideloading on your own TV.

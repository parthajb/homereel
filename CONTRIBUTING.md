# Contributing to HomeReel

Thanks for helping. Bug reports, fixes, translations, docs and features are all welcome. For anything bigger than
a fix, open an issue first so we can agree on the approach before you spend a weekend on it.

## What fits HomeReel

HomeReel is a home video server on an old phone and a TV app for it. These principles decide most questions:

- **It stays on the home network.** No accounts, no cloud, no analytics or telemetry. The TV app never goes online:
  it talks only to the phone, on private addresses.
- **Only the phone looks things up**, and only with keys the person adds (TMDB, OMDb). Titles and years are all
  that leave the phone.
- **Your own videos only.** HomeReel plays files people own. Features that download, scrape or stream content from
  elsewhere, or get round DRM or copy protection, won't be accepted.
- **Old hardware first.** The phone may be years old, and the TV may have 1–2 GB of RAM. Both apps support Android
  8.0 (API 26) and up, and the TV app stays light: no blur, no autoplaying video, nothing polling in the background.
- **Plain words.** Text on screen is short, plain English, and says what to do next. The app uses British spelling
  ("optimise", "colour").
- **Nothing breaks for people who don't use a feature.** New behaviour is off, or invisible, until someone turns it on.

## Getting set up

You need JDK 17 and the Android SDK (platform 34, build-tools 34). Put the SDK path in `local.properties`
(`sdk.dir=…`; it's ignored by git).

Run what the checks run before you open a pull request:

```bash
./gradlew :shared:test :server:lintRelease :tv:lintRelease :server:assembleRelease :tv:assembleRelease
```

Lint must stay at zero errors. The unit tests live in `shared/src/test/`; logic that doesn't need Android (parsing,
matching, rules) belongs in `shared/` with a test.

## Trying a change

You don't need a spare phone and TV: one Android emulator can be both.

1. **Start an emulator** (a phone image, API 30 or later) and install the debug builds:
   `./gradlew :server:installDebug :tv:installDebug`.
2. **To see the TV layout** on a phone emulator, make it TV-shaped:

   ```bash
   adb shell wm size 1080x1920 && adb shell wm density 320
   ```

   `adb shell wm size reset && adb shell wm density reset` puts it back. An Android TV emulator image works too.
3. **Make some test videos** with ffmpeg, named like real films or episodes, and push them to the emulator:

   ```bash
   ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=25 -f lavfi -i sine=frequency=440 -t 20 -c:v libx264 -pix_fmt yuv420p -c:a aac "Dangal (2016).mp4"
   ```

   ```bash
   adb push "Dangal (2016).mp4" /sdcard/Movies/HomeReelTest/
   ```

   In HomeReel Server, tap **Choose movie folder** and pick `Movies/HomeReelTest`.
4. **Reach the server from your computer:** `adb forward tcp:18080 tcp:8080`, then open
   `http://127.0.0.1:18080/admin`. The TV app on the same emulator usually finds the server by itself; if it
   doesn't, type `127.0.0.1:8080`.
5. **Online movie info without real keys:** run `python3 tools/mock_tmdb.py` and point a debug build at it. Its
   first lines say which settings to fill in, and how to simulate a network blip or an OMDb outage.
6. **A USB drive:** `adb shell sm set-virtual-disk true` gives the emulator a removable disk. `sm list-disks`
   and `sm partition <disk> public` format it, and `sm unmount` / `sm mount` with its volume id unplug and replug it.

Changes to playback, the Home screen or the remote's buttons should also be tried on a real Android TV, since
emulators don't have a TV's decoders, display modes or remote. Say what you tested on in the pull request.

## Code

- **Match the code around you:** Kotlin's official style, the same naming, and comments that explain *why*, not
  what. No new libraries without an issue first; each one makes the APKs bigger.
- **Keep the API compatible.** A new phone app must work with an older TV app and the other way round, since people
  update them at different times. Give new fields in `shared/` models a default, and never rename or remove one.
  Update the API table in the README when you add or change a route.
- **Check permissions on the server.** Every route decides whether a viewer (the TV) may use it or only the admin
  page, and what a kids profile may see. See [SECURITY.md](SECURITY.md) for what must hold.
- **Screenshots and logs** in issues and pull requests must not show IP addresses, Wi-Fi names, account emails,
  serial numbers or people's names. Blur them first.

## Pull requests

- **One topic per pull request.** Small is fine.
- **The description** says what changed and why, what you tested it on (devices and Android versions), and has a
  screenshot for anything visible.
- **Titles** are short and say what changed: "TV: remember the subtitle size", "Server: rescan when a USB drive
  comes back".
- **The changelog:** add a line under a new "Unreleased" heading in [CHANGELOG.md](CHANGELOG.md).

By contributing, you agree that your contribution is released under the project's [MIT licence](LICENSE).

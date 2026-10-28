# Roadmap

Where HomeReel is going, and what it won't do. This list is a plan, not a promise: items move when testing on a real
phone and TV says they should. Every item follows the principles in [CONTRIBUTING.md](CONTRIBUTING.md): it stays on
the home network, plays your own videos, runs on old hardware, and stays off until someone turns it on.

To suggest something, open a feature request. To work on an item, say so on its issue first.

## Where it stands (0.6.2)

0.1 to 0.6 built the whole loop: the phone server, the TV app, the web app and the admin page, plus:
- online movie info, TV shows, profiles with a kids PIN, search and My List;
- lighter copies of heavy files, frame-rate matching and Skip intro;
- Health, the event log, backup, extra folders and updates without a cable;
- Private videos, and torrent downloads with search feeds.

Tested on a Redmi Note 9 Pro (Android 13), a OnePlus TV (Android TV 11) and the emulator.

---

## Now: 0.7, ship it properly

Make what exists easy to trust and to build, before adding more.

| Item | What it means | Done when |
|---|---|---|
| **Tag 0.6.2** | `dev` is merged into `main`. Tag it `v0.6.2`, with GitHub release notes from the changelog; there are no APKs, as people build from source. | The tag exists, and `main` builds clean from a fresh clone |
| **Checks on every pull request** | A GitHub Actions workflow that runs the README's build command: tests, lint and both release builds. | A pull request shows a green or red check |
| **Tests for the server's rules** | Move logic that doesn't need Android into `shared/`, with tests: feed redirects and the home-network rule, request limits, the result cache, backup restore matching, and Private's rules. | The torrent and Private safety rules each have a test that fails if they're loosened |
| **Smaller phone app** | The phone app is about 30 MB, mostly libtorrent's native code for both 64-bit and 32-bit phones. Build for `arm64-v8a` (and `armeabi-v7a` for older phones) as separate APKs, with the update-without-a-cable refusing the wrong one. libtorrent stays uncompressed: compressing it would halve the download but take more room on the phone once installed. | Each APK is about half the size (17 MB and 15 MB), and updating works on both kinds of phone |
| **Clearer feed errors** | When a search feed fails, say why in plain words: the name didn't resolve, the connection was refused, it timed out, or the site answered with an error. A blocked site's DNS answer is reported as "your internet provider may block this site", without working around the block. | Each failure on the emulator shows its own message |
| **Screenshots** | Screenshots of the TV home, details, player, admin Settings and torrent search, for the README, with addresses and Wi-Fi names blurred. | The README has them |
| **TV on 0.6.2** | Update the OnePlus TV, so both apps run the same version again. | The TV shows 0.6.2 in About, and ADB is closed afterwards |

## Next: 0.8, a better evening on the sofa

Smaller things that streaming apps do and HomeReel doesn't yet. All are on the TV, with settings on the admin page.

| Item | What it means |
|---|---|
| **Subtitle timing and style** | On the TV, shift subtitles earlier or later in 0.1 s steps, remembered per file, and choose the size and background. In the web app, the same shift. |
| **Find subtitles (optional)** | With an [OpenSubtitles](https://www.opensubtitles.com) key the person adds, the phone fetches subtitles in each profile's language for a film or episode that has none. It sends the file's hash and title, and nothing else. Off without a key. |
| **Night mode** | Dynamic range compression for loud films watched quietly, on the TV's player menu, and a volume boost remembered per film. |
| **Collections** | TMDB already gives each film its collection. Add a "From the same collection" row on a film's page, and a Collections section in Genres. |
| **Continue watching on the TV's home screen** | The Android TV "Play next" row shows what to carry on with, filled in from the phone while the app is open. Nothing polls in the background. |
| **"Hey Google, play Dangal on HomeReel"** | A search provider in the TV app, so the TV's own search and voice search find films on the phone. It works only while the phone is reachable, and never goes online. |

## Later: 0.9, around the house

| Item | What it means |
|---|---|
| **Phone as a remote** | Browse in the web app on a phone and press **Play on TV**. The TV app opens a small API on the home network, paired like the phone is, and only answers paired devices. |
| **Home videos library** | Clips that are never looked up already exist. Give them their own section, sorted by date and grouped by day ("12 October 2026 · 14 clips"), with the date read from the file. |
| **Watching away from home** | A tested guide for Tailscale on the phone and a laptop or travel TV stick. Nothing exposed to the internet, and nothing built into the app. |
| **Translations** | Move every on-screen string into resources, then accept translations of the TV app, the web app and the admin page. Hindi first, if someone offers. |

## Towards 1.0

1.0 means someone else can set HomeReel up from the README alone and keep it running for a year. These need to be
true first:
- **A versioned API:** the TV, web app and phone check each other's API version and say plainly when one needs
  updating, instead of failing.
- **A versioned backup format,** with restore tested from every earlier version.
- **Upgrades tested from each release** on a real phone and TV, not only the emulator.
- **Tested on more hardware:** at least one more phone and one more TV, ideally a 1 GB Android TV and a Google TV,
  with results in the README.
- **Checks on every pull request,** and every safety rule covered by a test (see 0.7).

## Ideas waiting for a reason

Possible, but not planned until someone needs them:
- **Music:** albums and artists on the TV, with a Now playing row. Worth it only if people keep music files.
- **Chromecast from the web app:** it needs Google's Cast library in the web app, and the phone would then serve the
  file to the Chromecast. Worth it for homes without an Android TV.
- **A second phone as a server:** two libraries on one TV.

## Not planned

These come up, and the answer is no. Most of them would break a principle; the reason is given so the question
needn't be asked again.

| Idea | Why not |
|---|---|
| Accounts, a cloud service, sync between homes, analytics | HomeReel stays on the home network, with nothing to sign up for. |
| Prowlarr or Jackett built into the phone app | They're .NET servers. Android won't let an app run programs it downloads, they'd add hundreds of MB, and their site scrapers need constant upkeep. Run them on a computer and add them as search feeds. |
| Browsing, charts or recommendations of torrents | HomeReel searches for what you ask, and suggests nothing to download. |
| Working around a provider's blocks, or proxies and VPNs in the app | Use Private DNS, a VPN on the phone, or a Prowlarr or Jackett elsewhere, as you choose. |
| Live TV channels or IPTV playlists | They stream from elsewhere, which makes them a different app. |
| Trailers | They'd come from YouTube, and the TV app never goes online. |
| A muted preview when a poster is focused | It means autoplaying video on TVs with 1–2 GB of RAM. Backdrops stay still. |
| Converting while you watch | The phone streams each file as it is. Heavy files get a lighter copy made in advance, while it charges. |
| Anything that plays DRM-protected video | Keep one streaming app for those. |

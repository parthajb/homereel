# HomeReel

Turn an old Android phone into a movie server for your home Wi-Fi, and watch from an Android TV app that feels like
a streaming service.

- **HomeReel Server** (`server/`, package `com.parthajb.homereel.server`) runs on the phone. It serves the videos in
  the folders you pick (a USB drive too) over a small REST API, with seeking, posters, subtitles and resume points.
- **HomeReel** (`tv/`, package `com.parthajb.homereel.tv`) runs on the TV. It finds the phone by itself, shows
  your films and shows in rows, and plays them.
- **The web app** at `http://<phone ip>:8080` lets a laptop, phone or tablet on the Wi-Fi watch too, with the same
  profiles, My List and resume points as the TV.
- **The admin page** at `http://<phone ip>:8080/admin` works in any browser on the Wi-Fi. Use it to add, edit and
  delete videos, and to manage profiles, settings and updates.

**Everything stays on your network.** There's no account, no cloud and no tracking, and nothing is copied to the
TV. The TV app never goes online. The phone only does if you add a free TMDB key for posters and details, and then
it sends titles and years and nothing else; with an OpenSubtitles key, also each video's file hash and size, to find
subtitles timed for it.

**HomeReel plays files you already have:** your own rips, DRM-free purchases, home videos. It doesn't find or
stream anything from elsewhere, and it can't play anything protected by DRM. It can fetch a `.torrent` or magnet
link you give it, but only once you turn that on ([Torrent downloads](#torrent-downloads)), and only for things you're
allowed to download.

**Status:** a personal project, shared under the [MIT licence](LICENSE). It's tested on a Redmi Note 9 Pro
(Android 13) as the server and a OnePlus TV (Android TV 11) as the TV, and on the Android emulator. Both apps need
Android 8.0 or later. There are no prebuilt downloads: you build the apps yourself, below.

## How it works

```
 Admin page (/admin) ──upload / edit──▶  Phone: HomeReel Server  ◀──list / stream / progress── TV: HomeReel
                                         foreground service, port 8080
 Web app (/) ───list / stream / progress─▶ your folders of videos (SAF)
                                         advertised as _homereel._tcp
```

- **Streaming:** the phone streams each file as it is, with HTTP Range requests, so seeking works and the phone
  never transcodes. Whether a file plays depends on the **TV's** decoders.
- **Codec checks:** the TV app checks every movie against its own decoders and reports anything it can't play.
  Those files get a ⚠ badge on the TV and are listed on the phone and on the admin page.
- **Resume points** are stored on the phone, so they follow you to any TV paired with it.

## Build

You need JDK 17 and the Android SDK (platform 34, build-tools 34). Put the SDK path in `local.properties`
(`sdk.dir=…`).

```bash
./gradlew :shared:test :server:lintRelease :tv:lintRelease :server:assembleRelease :tv:assembleRelease
```

The APKs land in `server/build/outputs/apk/release/` and `tv/build/outputs/apk/release/tv-release.apk`. They are
signed with your debug key, so later builds from the same computer install over them.

The phone app comes in two copies, one per kind of phone, because libtorrent's native code is most of its size:

| File | For | Size |
|---|---|---|
| `server-arm64-v8a-release.apk` | 64-bit phones: almost every phone from 2017 on | about 17 MB |
| `server-armeabi-v7a-release.apk` | 32-bit phones, and phones running a 32-bit Android | about 15 MB |

`adb shell getprop ro.product.cpu.abi` says which a phone needs.

## Set up the phone

1. **Install the app:** `adb install -r server/build/outputs/apk/release/server-arm64-v8a-release.apk` with USB
   debugging on (the `armeabi-v7a` copy for a 32-bit phone). Or copy the APK over and open it in the phone's file
   manager.
2. **Choose the folder:** open **HomeReel Server**, allow notifications, then tap **Choose movie folder** and pick
   (or create) a folder such as `Movies`. Sub-folders are scanned too.
3. **Let it run in the background:** tap **Keep running in the background** and allow it. Otherwise Android may
   stop the server overnight.
4. **Leave it running:** keep the phone on its charger, on the Wi-Fi.
   - Use the phone's charge limit if it has one (often called "Protect battery", 80–85%).
   - Keep it somewhere ventilated: old batteries can swell.

The screen shows the address, the Wi-Fi band and speed, and the **pairing code**. The server starts again by
itself after a reboot.

## Set up the TV

1. **Install the app:** with ADB to the TV, run `adb install -r tv/build/outputs/apk/release/tv-release.apk`.
   If `adb install` is refused on your TV, the [cleanyourtv](https://github.com/Gathin23/cleanyourtv) tools'
   `tools/b64push.py` copies the APK over first; then run `pm install -r` on the TV.
2. **Pair:** open **HomeReel**, pick the phone from the list (or type its address), and enter the 6-digit code
   the phone shows.
3. **Optional:** add `com.parthajb.homereel.tv` to the launcher's dock.

Switch ADB off on the TV again when you're done.

## Watching in a browser

Open `http://<phone ip>:8080` in a browser on the home Wi-Fi: on a laptop, a phone or a tablet. The phone's screen
shows the address.

- **Pairing:** the first time, enter the 6-digit code from the phone. The browser pairs as a viewer, like a TV: it
  can watch and save progress, but not change anything. A browser already paired for the admin page doesn't need
  to pair again.
- **Sections** in the header, as on the TV: Home, Movies, TV shows, Genres (with a page per genre), My List and
  Private. Each has its own address, so the browser's Back button and bookmarks work.
- **What's the same as the TV:** "Who's watching?", with the parent PIN to leave a kids profile; the same rows
  (Continue watching, My List, Because you watched, shows, genres); search; details with cast and More like this;
  seasons and episodes; resume points shared with the TV; Skip intro; and Next episode at the credits.
- **The player:** a seek bar that shows the time as you drag; −10 s and +10 s; volume; full screen and
  picture-in-picture; and one menu for subtitles (with three text sizes), the audio language where the browser allows
  it (Safari), and speed: 1×, 1.25×, 1.5× or 2×.
  - On a phone or tablet, tap to show the controls, and double-tap the left or right side to skip 10 seconds.
  - The phone's lock screen and a laptop's media keys control it too.
- **Subtitles:** subtitle files beside the video (`.srt`, `.vtt`) are in the player's menu, and each profile's
  choice is remembered. Subtitles inside an MKV only show on the TV. **Subtitle timing** in the menu moves them
  earlier or later, as on the TV.
- **What a browser can play** depends on the browser, not the TV:
  - MP4 with H.264 video and AAC sound plays everywhere, and so does a lighter copy made by the phone.
  - MKV plays in Chrome and Edge, and may not in other browsers.
  - HEVC (H.265) video plays in Safari and Edge, and in Chrome on most recent computers.
  - Dolby Digital sound plays in Safari and Edge only; DTS and TrueHD play in no browser.
  - The details page warns when a file may not play, and the TV plays them all.
- **Keyboard:** Space or K to pause, ← and → (or J and L) to skip 10 seconds, ↑ and ↓ for volume, M to mute, F for
  full screen, C for subtitles, `<` and `>` for speed, N for the next episode, Esc to go back, and / to search.
- **Add it to your home screen** on a phone or tablet (Share → Add to Home Screen) to open it like an app.

It only works on the home network, like everything else in HomeReel. To watch away from home, use a VPN such as
Tailscale on the phone and your device rather than opening port 8080 to the internet:
[Watching away from home](docs/away-from-home.md) shows how.

## Updating without a cable

ADB is only needed for the first install. After that:

1. Build both apps on the same computer as before (the same key signs them).
2. On the admin page, open **Settings → App updates** and upload `tv-release.apk` with **Upload TV app…** and
   the phone's copy (`server-arm64-v8a-release.apk` on a 64-bit phone) with **Upload phone app…**.
   - The phone only keeps a HomeReel app signed with the same key as itself, and a phone app newer than the one
     running. Android refuses an update signed with another key anyway.
   - It also refuses the copy for the other kind of phone, and says which file it needs.
3. **TV:** the next time HomeReel opens, it says an update is ready, and the Settings row starts with
   **Update to 0.x.y**. Choose it, then **Update**.
4. **Phone:** a notification says the update is ready, and HomeReel Server shows **Install update**. Tap it, then
   **Update**. The server restarts by itself.

The first time, Android asks to allow HomeReel to install apps (on the TV: Device Preferences → Security &
restrictions → Unknown sources → HomeReel). Google Play Protect may ask to scan the app, since it has never seen it.
**Scan app** sends the APK to Google; **More details → Install without scanning** keeps it on the device.

The version is set in `server/build.gradle` and `tv/build.gradle` (the `versionCode` default, now 3, and
`versionName`): raise both for every update. `-PhomereelVersion=N` builds a copy with version N, for trying the update path.

## Adding and removing movies

| Way | How |
|---|---|
| Any browser on the Wi-Fi | Open `http://<phone ip>:8080/admin` and enter the pairing code once. Drag files in to upload them. Click a movie to edit its thumbnail, title, year, IMDb rating, genres and description, or to delete it. |
| USB cable | Plug the phone into a computer and copy files into the folder (on a Mac: OpenMTP or Android File Transfer). |
| On the phone | Download or move files into the folder with any file manager. |
| A torrent | Turn on [Torrent downloads](#torrent-downloads), then drop the `.torrent` file or paste a magnet link on the admin page. |

- **Uploads** go in 8 MB pieces. If the Wi-Fi drops, the upload carries on from where it stopped, and it also
  carries on if you start the same file again later.
- **Picking up changes:** files copied any other way show up at the next automatic scan (every 15 minutes), or
  straight away with **Rescan** on the phone, the TV or the admin page.
- **Deleting** a movie removes its subtitles and poster too. The TV app has no delete button.

**Naming** helps:

| File | Becomes |
|---|---|
| `Movie Name (2019).mkv` or `Movie.Name.2019.1080p.WEB-DL.mkv` | "Movie Name", 2019 |
| `Movie Name (2019).jpg` beside it, or `poster.jpg` in a folder with one movie | the poster (otherwise the phone grabs a frame) |
| `Movie Name (2019).srt`, `Movie Name (2019).en.srt`, `….hi.srt` | subtitles, labelled English, Hindi… |

Supported files are `.mp4 .m4v .mkv .webm .avi .mov`, with `.srt`/`.vtt` subtitles.

## Torrent downloads

Off until you turn it on: **Settings → Torrent downloads** on the admin page.

- **Adding:** drop a `.torrent` file where you add movies (with your videos, if you like), or paste a magnet link in
  the box below the drop area. Videos upload as usual; torrents go to **Downloads** on the main screen.
- **In its own time:** by default it only downloads while the phone is charging. You can also limit it to hours
  (overnight, say), cap the download and upload speeds, and have it slow to a trickle while someone is watching, so
  the TV doesn't stutter.
- **Only what HomeReel can play:** of the files in a torrent it downloads just the videos and subtitles, never text
  files, pictures, programs or the short "sample" clip.
- **When it's done:** the files are copied into the movie folder (or Private, for a torrent added in the Private
  view), the library rescans, and the phone deletes its own copy and stops sharing it.
- **In Downloads:** progress, speed and time left; Pause, Resume and Remove (which deletes what it has downloaded so
  far); Try again after a problem such as a full phone; and Clear once it's in the library.
- **Space:** a torrent is refused if it wouldn't leave 1 GB free, and downloads pause when space runs low.
- **Privacy and the law:** while it downloads, the phone exchanges pieces with the other people sharing that torrent,
  and they can see your internet address. HomeReel never opens ports on your router (UPnP and NAT-PMP are off). Only
  download what you're allowed to, such as public-domain and Creative Commons films, or copies of your own.
- **Size:** libtorrent is most of the phone app, about 17 MB on a 64-bit phone. The engine only starts when there's
  something to download.

### Torrent search (optional)

Add one or more **search feeds**, and a search box appears where you add movies. A search asks every feed that's on,
all at once, and shows one list; a result's **Download** button sends it to Downloads, the same as pasting its magnet
link. HomeReel doesn't come with any feed set up.

- **Kinds of feed** (Settings → Downloads → Torrent search → **+ Add a feed**):
  - **Torznab feed:** one site's own [Torznab](https://torznab.github.io/spec-1.3-draft/) search. A site's API page
    gives the address, usually ending in `/torznab` or `/api`. The key is optional: leave it empty for a site that
    doesn't need one.
  - **[Prowlarr](https://prowlarr.com):** the address you open Prowlarr at, such as `http://192.168.1.20:9696`. The
    key is under Settings → General → Security. Searches use Prowlarr's own search, which asks every torrent indexer
    switched on in it.
  - **[Jackett](https://github.com/Jackett/Jackett):** the address you open Jackett at, such as
    `http://192.168.1.20:9117`. The key is at the top right of its dashboard. Searches ask every indexer set up in
    Jackett. To search just one, paste its **Copy Torznab Feed** address instead; a key inside a pasted address is
    picked up and kept out of the address.
  - Prowlarr and Jackett run on a computer or NAS at home (Docker images exist for both); the phone only talks to them.
    Sites that offer Torznab themselves need neither.
- **Managing feeds:** each has a name (shown beside its results), a tick to switch it on or off, Edit and Remove, up
  to 20. **Test connection** tries the address and key without saving them, says whether they work, and lists the
  indexers Prowlarr or Jackett will ask (or picks up a Torznab site's own name).
- **The keys** stay on the phone; the admin page only sees whether each is set. A feed on the internet must use
  `https://`; plain `http://` is only allowed on your home network (or a Tailscale network, `100.64.0.0/10`).
- **Results:** Movies, TV or everything, up to 100 from each feed, with size, seeders, peers and who found it ("YTS via
  Prowlarr"). The same torrent from several feeds shows once, keeping the copy with a magnet, most seeders first. A
  feed that is slow or fails doesn't spoil the others: the page says which didn't answer and why. HomeReel waits up to
  a minute, since Prowlarr and Jackett wait for their slowest indexer. Seeders are what the sites last saw, so a
  torrent may still have nobody sharing it.
- **Light on the sites:** HomeReel only asks when you press Search. Each feed has its own limits, kept across
  restarts: at most once a second, 90 times an hour and 900 times a day. The same search within 15 minutes comes from
  a cache, so the same address is never asked twice in a minute. A feed that says "too many" waits 15 minutes on its
  own.
- **What the result can do:** HomeReel keeps only a magnet's hash, name and trackers, so a result can't make the phone
  fetch other web addresses. Download links are only fetched from the address of the feed that gave them (Prowlarr's
  and Jackett's are always on theirs). When one redirects, HomeReel takes a magnet, follows the feed's own address, or
  follows a public `https://` address without the key. It never follows one to another address on your home network.
  A result with neither a magnet nor a link on its feed's address is left out, and the page says how many were.
- **Privacy:** every feed that's on sees what you search for and your internet address.
- **From 0.6:** the indexer set up there becomes the first feed.

## TV series

Name episode files the usual way and HomeReel groups them into shows by itself:

| Layout | Becomes |
|---|---|
| `Panchayat S01E02.mkv`, `panchayat.s01e02.1080p.mkv`, `The Office 3x12.mp4` | the show, season and episode from the name |
| `Kota Factory/Season 1/03 - Physics.mkv`, `…/S02/Episode 4.mkv` | the show from the folder, season from "Season N", episode from the number |

Anything without a season and episode is a film.

- **On the TV:**
  - A **TV shows** row, and shows mixed into Recently added, Top rated and the genre rows.
  - **Continue watching** carries on each show with its next episode.
  - **The show page:** Resume or Play, a tab per season, and episode cards with titles, stills and progress.
  - **When an episode ends,** a "Next episode" countdown starts the next one after 10 seconds. **Play now**
    starts it at once, and **Cancel** goes back.
- **Online info:** with a TMDB key, shows are looked up as a whole: poster, backdrop, description, cast, creators,
  age rating, IMDb rating. Each episode's title, description and still come from TMDB's season listings.
- **On the admin page:** a **TV shows** tab. Each show has the same editing, Find on TMDB and thumbnail upload as a
  film, plus its list of episode files.

## Family profiles

- **Who's watching?** The TV asks when it opens, if there's more than one profile.
- **Each profile has its own:** Continue watching, My List, and "Because you watched…" rows. Progress saved before
  profiles existed belongs to the first profile, "Family".
- **Kids profiles** only see titles rated for children: U, U/A 7+, G, PG, TV-Y, TV-Y7, TV-G, TV-PG.
  - A title with no rating, like a home video, stays hidden unless you set **Kids profiles → Show on kids profiles**
    in its editor. **Hide from kids profiles** works the other way.
  - Private videos never show on a kids profile, even while Private is unlocked.
  - The phone enforces this, not just the TV app: a kids profile asking for anything else gets "not found".
- **Parent PIN:** set on the admin page (Settings → Family). Leaving a kids profile on the TV then asks for it, and
  the phone checks it; five wrong tries lock it for a minute. A kids profile's Settings row only offers "Switch
  profile".
- **Managing profiles:** add, rename, mark as kids or delete them in Settings → Family.

## Search and My List

- **Search:** use **Search** at the top of the home screen (press Up from the first row), or the remote's search key.
  It searches as you type across titles, actors, directors, creators, genres and years, for films and shows. It
  only finds what the current profile can see.
- **My List:** **♥ My List** on any film or show page adds it to the profile's My List row on the home screen.

## Smooth playback

- **Lighter copies:** files over 60 Mbps, like 4K drone footage, can stutter over Wi-Fi. A 90 Mbps clip did on this
  setup; 30 Mbps played fine.
  - The admin page marks them **Heavy · 90 Mbps**, and **Make a lighter copy** has the phone re-encode the file with
    its hardware encoder: the same resolution at about 24 Mbps for 4K, 10 for 1080p.
  - The copy lives in the app's own storage, the original is never touched, and the TV plays the copy.
  - **Use the original** removes it.
  - With **Settings → Make lighter copies of very heavy files** on (the default), the phone does this by itself
    while it's charging.
  - DTS and TrueHD audio can't be decoded on the phone. Convert those on a computer (see Troubleshooting).
- **Match frame rate** (TV Settings row, on by default): switches the TV to the film's frame rate (24p, or 50 Hz
  for 25 fps) so pans don't judder. The screen may go dark for a moment as it switches.
- **Skip intro and credits:** in a show's editor on the admin page, set each season's intro start and end, and how
  long the credits run.
  - During the intro, the TV offers **Skip intro**.
  - When the credits start, it offers the next episode. **Cancel** lets the credits play.

## Keeping it safe and running

- **Health** (admin page): the phone's battery charge and temperature, whether it's charging, the Wi-Fi band and link
  speed, uptime, the last scan, each folder's free space, and anything that needs attention.
  - Warnings cover a battery at 42°C or more, the phone not charging, no Wi-Fi, a folder that isn't there, less
    than 2 GB free, and a failed scan.
  - The phone checks itself every five minutes and writes a hot battery into the event log once an hour.
- **Event log:** the last 200 starts, stops, scans, failed lookups, failed copies and crashes, newest first on the
  Health panel. A crash is written down before the server dies; Android then restarts the server by itself.
- **More folders, such as a USB drive:** on the phone, tap **Add another folder (e.g. a USB drive)** and pick a
  folder on the drive through a USB-C OTG adapter. Its videos join the library.
  - When the drive is unplugged, its videos are hidden from the TV and marked **Offline** on the admin page. Their
    details and resume points are kept, and come back when the drive does.
  - **Remove an extra folder** takes it out of the library. The files stay on the drive.
  - The Private folder is only ever the one in the main folder; files on an extra folder can't move to Private.
  - Moving or renaming the picked folder on the phone takes away HomeReel's access to it. Remove it and add it again.
- **Backup** (Settings): **Download a backup** saves one JSON file with everything typed in or looked up, everyone's
  progress, profiles and My Lists. It doesn't include the videos, the lookup keys or the parent PIN.
  - **Restore from a backup…** on a new phone, or after a reset, matches files by name and size and keeps whichever
    resume point is newer.
  - Pictures aren't in the backup: **Refresh info** fetches TMDB's again, and an uploaded thumbnail needs uploading again.

## Private videos

Personal videos go in a folder called **Private** at the top of the movie folder. Use **🔒 Upload into the Private
folder** on the admin page, **Move to Private** on a film, or copy files there by cable.

- **Locked (the default):**
  - The TV doesn't list them, and asking for one directly gets "not found".
  - That covers the list, the details, the stream, the poster and saving progress.
- **On the admin page** they're hidden too: not in All, the filters or search. **Settings → Private folder → Open
  Private videos** shows them, and uploads made there go into Private.
- **Unlocking:** on the admin page, **Settings → Private folder** unlocks them on the TV for 30 minutes to 4 hours.
  They then show in their own **🔒 Private** row on the TV and nowhere else. While Private is unlocked, the admin
  page's main screen shows a reminder with **Lock now**.
  - Private locks itself again when the time runs out, or at once with **Lock now**.
  - Only a browser paired as admin can unlock it; a TV can't.
- **Kept out of other places:**
  - Private videos are never looked up online.
  - They're never grouped into shows.
  - A `.nomedia` file keeps them out of the phone's gallery and video apps.
- **What it doesn't protect:** anyone who can unlock the phone itself can still open the folder in a file manager.
  Private protects the TV, not the phone; keep the phone's screen lock on.

## Online movie info

With a free TMDB key, the phone fills in each movie's details by itself:
- poster and backdrop
- description and tagline
- genres
- cast, director and collection
- age rating, Indian first (U, UA 13+, A), else American
- rating

Add a free OMDb key as well to get the real **IMDb** rating; without one, TMDB's own score is shown, labelled "TMDB".

1. **Get the keys:**
   - **TMDB:** make an account at themoviedb.org, then Settings → API → request a key. Either the "API Key" or the
     "API Read Access Token" works.
   - **OMDb (optional):** request a free key at omdbapi.com/apikey.aspx.
2. **Add them:** on the admin page, click **⚙︎ Settings** and paste the keys. The TMDB key is checked straight away.
   The keys stay on the phone; the page can only see whether they're set.

How it behaves:
- **Only the phone goes online.** It downloads the pictures once and keeps them; the TV never contacts TMDB.
- **Only confident matches fill in by themselves:**
  - The titles must match closely, word for word.
  - The year must match, give or take one, when the file name has one.
  - Everything else waits under **Needs a match**. Open the movie, then **Find on TMDB**, then **Use this**.
- **Home videos are never looked up:** camera and phone clips (`DJI_…`, `VID_…`, `IMG_…`, `PXL_…`, `GOPR…`) and
  anything in a folder called "Home videos". **Don't look up** marks any other file the same way.
- **What you type always wins.** A field you change on the admin page shows "✎ yours", and **Refresh info** never
  overwrites it. **Use TMDB's** hands it back.
- **Pace:** lookups run in the background, one title a second, after each scan. A lookup that fails because of the
  network is retried a day later.

**Privacy:** movie titles and years go to TMDB, and IMDb ids to OMDb. Nothing else leaves the phone.

This product uses the TMDB API but is not endorsed or certified by TMDB.

### Finding subtitles (optional)

With a free [OpenSubtitles](https://www.opensubtitles.com/consumers) API key in **Settings → Movie info**, the phone
finds subtitles for films and episodes that have none, in the languages listed there (`en, hi`, up to five).

- **What's sent:** each video's OpenSubtitles hash (worked out from the start and end of the file), its size, its
  title or IMDb id, and the languages. Private videos and home videos are never looked up.
- **Where they go:** into the phone app's own storage, not the movie folder. They show on the TV and in the web app as
  "English · OpenSubtitles", after any subtitle files beside the video. Deleting a video deletes them too.
- **A few at a time:** the phone asks at most once a second, tries each video once a week, and stops for the day
  when OpenSubtitles' daily download limit is reached. The admin page says when it carries on.
- **By hand:** in a film's editor, **Playback → Find subtitles** looks for the languages it's missing straight away,
  and **Remove found subtitles** deletes them.

## On the TV

- **The TV's own home screen:** on Android TV and Google TV, what you're part way through shows in the TV's
  **Play next** row (up to ten), with a picture, so you can carry on without opening HomeReel first. It's updated
  while HomeReel is open, never in the background, and follows the profile watching; a kids profile empties it.
  Remove a title from the row and it stays out until you finish it. Some TV makers' home screens have no such row.
- **Home videos** have their own section on the TV and in the web app, and a row on Home: camera and phone clips
  (`VID_…`, `PXL_…`, `DJI_…`), anything in a "Home videos" folder, and anything marked **Don't look up**. They're
  grouped by the day they were recorded, newest first ("12 October 2026 · 14 clips"), with the date from the file's
  name when it has one, else from when the file last changed. A camera clip is called by its date ("Video, 12 October
  2026 at 10:15") until you rename it, and its page shows the other clips from that day. They stay out of Movies,
  Genres and the film rows.
- **The TV's own search and voice:** HomeReel's films and shows turn up in the TV's search, and "Hey Google, play
  Dangal on HomeReel" plays it (or the next episode of a show). When more than one title fits, HomeReel's search
  opens with what you said. Both use the library as the TV last loaded it, for the profile watching, never Private;
  playing still needs the phone. The copy is deleted when the TV is unpaired.
- **Home screen:**
  - A full-screen backdrop and the focused title's details at the top: rating, year, age rating, length, genres,
    description.
  - Rows underneath: Continue watching (with progress bars), Recently added, Top rated, one row per genre, then
    everything A–Z, and Settings.
  - **The menu along the top** (press Up from the first row): Home, Movies, TV shows, Genres, My List and, while
    it's unlocked, Private. Genres shows a tile per genre; choose one for its top rated, movies and shows. Under the genres, **Collections** has a tile per film series with two or more films here (from TMDB, like "Harry Potter Collection"), in the order they came out. A film's page shows the rest of its series above More like this. Sections
    with nothing in them aren't shown.
  - Back jumps to the top, then to Home, then leaves.
- **Details page:**
  - The tagline, cast and director.
  - Play, or Resume and Play from start, plus Mark as watched.
  - The video and audio formats, and why a file may not play.
  - **More like this**, from shared genres.
- **Player:**
  - With the controls hidden, **Left** and **Right** on the remote skip 10 seconds without opening them.
  - −10 s and +30 s buttons, and "Ends at …" beside the title, allowing for the speed.
  - **CC** opens a list: Off and each subtitle track, both `.srt` files and subtitles inside an MKV. At the end:
    - **Subtitle timing…** for subtitles that come too early or too late: Left and Right move them 0.1 s, Up and
      Down 1 s, and **Save** keeps it. The phone remembers it for that video, and the web app uses it too. It moves
      subtitle files beside the video, not subtitles inside an MKV.
    - **Subtitle style…** sets the size (Small to Extra large) and the background (an outline, a see-through box
      or a black box). Until one is picked, the TV's own captions settings apply.
  - The **speaker** button opens **Sound**: the audio languages of a dubbed film, then:
    - **Night mode** (Android 9 and later) makes loud parts quieter and voices clearer, for watching late. It stays on
      for the next film until it's turned off.
    - **Volume boost** raises a quiet film by 3, 6 or 9 dB. It's remembered for that film, or for every episode of a
      show.
    - Neither can change Dolby sound that the TV sends straight to an amplifier or soundbar (passthrough).
  - The **speed** button (it shows the speed, like 1.5×) offers 1×, 1.25×, 1.5× and 2×; voices keep their pitch.
    A TV that sends Dolby sound straight to an amplifier may only play at 1×.
  - The subtitle and audio language you pick are remembered for each profile on that TV.
  - The position is saved every 10 seconds and when you stop; a movie counts as watched at 95%.

## Wireless

Everything goes over the home Wi-Fi.
- **Same network:** the phone and TV must be on the same network. Guest networks and "client isolation" block it.
- **Bandwidth:**
  - 1080p files (5–15 Mbps) are fine on most Wi-Fi.
  - 4K remuxes (50–80 Mbps) can stutter on an old phone, especially on 2.4 GHz. Use 5 GHz if you can; the phone
    screen shows the band and link speed.
- **Address changes:** if the router gives the phone a new address, the TV finds it again by name. A DHCP
  reservation keeps the admin page's address stable.

## Security

- **Plain HTTP:** the API is plain HTTP on your home network, and the TV app refuses to connect to anything
  outside private address ranges. Anyone on your Wi-Fi can reach the server, but nothing works without pairing.
- **Pairing:**
  - The code is 6 digits. Five wrong guesses lock pairing for a minute.
  - **New pairing code** on the phone changes it; **Forget paired devices** signs everyone out.
- **Roles:**
  - The TV and the web app pair as a **viewer**: they can list, stream and save progress.
  - The admin page pairs as an **admin**: it can also upload, edit and delete.
  - A browser keeps its token in an `HttpOnly`, `SameSite=Strict` cookie, and every change also needs an
    `X-HomeReel` header that other websites can't send. A browser has one role: pairing it on the admin page makes
    it an admin, which can also watch.
- **Kids profiles** are filtered on the phone, so a kids profile gets no other titles, pictures or videos, whatever
  the browser or TV asks for. The parent PIN guards leaving a kids profile in the TV and web app; it isn't a
  password, so a determined older child at a computer could get round it.
- **Folder access:** the phone can read and write only the folders you picked.
- **Lookup keys:** the TMDB and OMDb keys stay in the phone app's private storage. The API only says whether
  they're set, even to the admin page.
- **Updates:** the phone only keeps an uploaded APK if it's the right HomeReel app, signed with the same key.

To report a security problem, see [SECURITY.md](SECURITY.md).

## API

All under `/api/v1`, JSON, with `Authorization: Bearer <token>` (or the browser's cookie). The TV and the web app
also send `X-Profile: <profile id>`; pictures and video in a browser use `?profile=<id>` instead, since `<img>` and
`<video>` can't send headers. Progress, My List and kids filtering follow it, and without it the first profile is
used. The web app is served at `/` and the admin page at `/admin`.

| Method and path | Role | What it does |
|---|---|---|
| `POST /pair` `{code, client, role}` | none | Returns `{token, role, serverName}`, and sets the browser's cookie |
| `GET /info` | viewer | Name, version, movie count, whether a scan is running |
| `GET /movies`, `GET /movies/{id}` | viewer | Films (not episodes) with tracks, subtitles, progress; private ones only while Private is unlocked |
| `GET /movies/{id}/stream` | viewer | The file, with `Range` support (`206`, `416`) |
| `GET /movies/{id}/poster`, `…/backdrop`, `…/subtitles/{n}` | viewer | Poster, wide backdrop, subtitle file |
| `PUT /movies/{id}/subtitle-offset` `{offsetMs}` | viewer | Move that video's subtitle files later (or earlier, negative), up to 10 minutes |
| `POST` / `DELETE /movies/{id}/found-subtitles` | admin | Find subtitles on OpenSubtitles now, or delete the ones found |
| `PUT /movies/{id}/progress` `{positionMs, durationMs}` | viewer | Saves the resume point |
| `POST /rescan` | viewer | Rescans the folder |
| `PUT /playability` `{client, items}` | viewer | What the TV can't play |
| `PATCH /movies/{id}` `{title, year, description, genres, imdbRating, reset}` | admin | Edits details; changed fields become yours, `reset` hands fields back to TMDB |
| `PUT` / `DELETE /movies/{id}/poster` | admin | Uploads a thumbnail (JPEG or PNG), or goes back to the automatic one |
| `POST /movies/{id}/match` `{tmdbId}`, `DELETE …/match` | admin | Matches to a TMDB film, or forgets the match |
| `POST /movies/{id}/refresh`, `POST …/skip` | admin | Fetches the details again; marks it a home video |
| `GET /admin/tmdb/search?q=&year=` | admin | Searches TMDB |
| `GET` / `PUT /admin/settings` | admin | Lookup keys (write-only) and automatic lookups on or off |
| `POST /admin/metadata/run` | admin | Looks up every title with no info yet |
| `GET /shows`, `GET /shows/{id}` | viewer | Shows with their up-next episode; one show with its episodes in order |
| `GET /shows/{id}/poster`, `…/backdrop` | viewer | A show's pictures |
| `PATCH /shows/{id}`, `PUT`/`DELETE …/poster`, `POST …/match`, `DELETE …/match`, `POST …/refresh`, `POST …/skip` | admin | The same editing and lookup actions as films |
| `GET /admin/tmdb/search?q=&year=&type=tv` | admin | Searches TMDB for shows |
| `GET /profiles`; `POST`, `PATCH`, `DELETE /profiles/{id}` | viewer / admin | List profiles; add, change or remove one |
| `POST /profiles/pin` `{pin}`; `PUT /admin/pin` `{pin}` | viewer / admin | Check the parent PIN (403, 429 after 5 wrong); set or clear it |
| `GET /me/list`; `PUT` / `DELETE /me/list/{m:id or s:id}` | viewer | The asking profile's My List |
| `POST` / `DELETE /movies/{id}/optimize` | admin | Make a lighter copy for the TV, or go back to the original |
| `GET /movies/{id}/stream?original=1` | viewer | The original even when a lighter copy exists |
| `PUT /shows/{id}/markers` `{season, introStartMs, introEndMs, creditsMs}` | admin | A season's skip-intro and credits times |
| `GET` / `PUT /admin/private` `{unlock, minutes}` | admin | Whether Private is unlocked, and until when; unlocks or locks it |
| `POST /movies/{id}/move` `{private}` | admin | Moves a film into or out of the Private folder, keeping its details and resume point |
| `DELETE /movies/{id}` | admin | Deletes the file, its subtitles and poster |
| `GET /admin/health` | admin | Battery, Wi-Fi, folders, warnings and the recent event log |
| `GET /admin/backup`; `POST /admin/restore` | admin | Download a backup file; restore one (returns how many films, shows and profiles came back) |
| `GET /updates/tv`, `GET /updates/tv/apk` | viewer | The TV app update the admin uploaded (version, size, SHA-256), and the APK |
| `GET /admin/updates`; `PUT` / `DELETE /admin/updates/{tv or phone}` | admin | Installed and uploaded versions; upload an APK (checked for package, key and version), or remove it |
| `GET /admin/status` | admin | Folder, free space, scan state, what can't play |
| `POST /admin/uploads` `{name, size, private}` | admin | Starts an upload (into Private if asked), or resumes one with the same name and size |
| `GET` / `PUT ?offset=N` / `DELETE /admin/uploads/{id}` | admin | Upload state, next piece, cancel |
| `GET /admin/torrents` | admin | Torrent settings, downloads and why they're waiting |
| `POST /admin/torrents[?private=1]` (the `.torrent` file as the body), `POST /admin/torrents/magnet` `{magnet, private}` | admin | Adds a download; refused while torrents are off |
| `PUT /admin/torrents/settings` | admin | On or off, charging only, hours, speed limits, slowing while someone watches |
| `POST /admin/torrents/{id}/pause`, `…/resume`, `…/retry`; `DELETE /admin/torrents/{id}` | admin | Steers one download; delete removes what it downloaded |
| `GET` / `POST /admin/torrents/feeds` `{kind: torznab\|prowlarr\|jackett, name, url, apiKey, enabled}` | admin | The search feeds; keys are write-only, and optional for a Torznab feed |
| `PUT` / `DELETE /admin/torrents/feeds/{id}` `{name, url, apiKey, clearKey, enabled}` | admin | Changes or removes one feed; fields left out stay as they are |
| `POST /admin/torrents/feeds/test` `{id?, kind, url, apiKey}` | admin | Tries the typed address and key (or a saved feed's) without saving them; says which indexers a search will ask |
| `GET /admin/torrents/search?q=&cat=movies\|tv\|all` | admin | Asks every feed that's on and merges the results; they carry an id (the hash, when known) and who found them, never magnets or links |
| `POST /admin/torrents/search/add` `{id, private}` | admin | Sends a recent search result to Downloads |

## Troubleshooting

| Problem | What to do |
|---|---|
| The TV doesn't list the phone | Open HomeReel Server on the phone and check it says Running. Type the address it shows on the TV instead. |
| "Can't reach the phone" | The phone may be off Wi-Fi or asleep with battery optimisation on. Allow **Keep running in the background**. |
| A movie shows ⚠ | The TV has no decoder for that video or audio. DTS and TrueHD audio are common culprits. Convert it, for example `ffmpeg -i in.mkv -map 0 -c:v copy -c:a aac -b:a 384k -c:s copy out.mkv` for the audio. A soundbar or receiver over HDMI ARC may decode it instead. |
| Stutters in 4K | Wi-Fi bandwidth. Move the phone closer to the router or onto 5 GHz, or use a smaller copy. |
| No poster | The phone couldn't decode a frame. Add `Movie Name.jpg` beside the file. |
| "The app lost access to the folder" | Tap **Choose movie folder** and pick it again. |
| "HomeReel lost access to the folder …" (an extra folder) | It was moved or renamed. **Remove an extra folder**, then add it again. |

## Layout

| Path | What it is |
|---|---|
| `shared/` | Plain Kotlin: API models, Range parsing, title parsing, the playability rules, and their unit tests |
| `server/` | Phone app: NanoHTTPD server, Storage Access Framework scanner, uploads, NSD, the admin page and the web app (`src/main/assets/admin.html`, `watch.html`) |
| `tv/` | TV app: AndroidX Leanback and Media3 ExoPlayer |
| `docs/` | Guides, such as [watching away from home](docs/away-from-home.md) with Tailscale |
| `tools/mock_tmdb.py` | A fake TMDB and OMDb with made-up data, for testing online movie info without keys |
| `tools/mock_opensubtitles.py` | A fake OpenSubtitles, for testing Find subtitles without a key |

## Contributing

Bug reports, fixes and ideas are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) explains what fits HomeReel, how to
try a change on the Android emulator without a spare phone or TV, and what a pull request needs. Changes are listed
in [CHANGELOG.md](CHANGELOG.md), and what's planned in [ROADMAP.md](ROADMAP.md). Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md).

## Licence and credits

HomeReel is released under the [MIT licence](LICENSE).

It builds on:

| Project | Used for | Licence |
|---|---|---|
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | The phone's web server | BSD 3-Clause |
| [AndroidX Media3](https://github.com/androidx/media) (ExoPlayer, Transformer) | Playback on the TV; reading tracks and making lighter copies on the phone | Apache 2.0 |
| [AndroidX Leanback](https://developer.android.com/jetpack/androidx/releases/leanback) and RecyclerView | The TV interface | Apache 2.0 |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | The API's JSON | Apache 2.0 |
| [libtorrent4j](https://github.com/aldenml/libtorrent4j) and [libtorrent](https://www.libtorrent.org) | Torrent downloads on the phone | MIT; BSD 3-Clause |
| [Material icons](https://fonts.google.com/icons) | Icons in the TV app | Apache 2.0 |

Movie and show details and pictures come from [TMDB](https://www.themoviedb.org), and IMDb ratings from
[OMDb](https://www.omdbapi.com), using keys each person gets for themselves. This product uses the TMDB API but is
not endorsed or certified by TMDB. HomeReel isn't affiliated with Netflix, Google, TMDB, IMDb or any device maker.

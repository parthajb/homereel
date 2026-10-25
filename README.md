# HomeReel

Turn an old Android phone into a movie server for your home Wi-Fi, and watch from an Android TV app.

- **HomeReel Server** (`server/`, package `com.parthajb.homereel.server`) runs on the phone. It serves the movies
  in one folder over a small REST API, with seeking, posters, subtitles and resume points.
- **HomeReel** (`tv/`, package `com.parthajb.homereel.tv`) runs on the TV. It finds the phone by itself, shows
  your movies in rows and plays them.
- **The admin page** at `http://<phone ip>:8080/admin` works in any browser on the Wi-Fi. Use it to add, rename
  and delete movies.

Everything stays on your network. There's no account, no cloud and no internet access, and nothing is copied to
the TV.

## How it works

```
 Laptop browser ──upload / delete──▶  Phone: HomeReel Server  ◀──list / stream / progress── TV: HomeReel
                                      foreground service, port 8080
                                      one folder of movies (SAF)
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

The APKs land in `server/build/outputs/apk/release/server-release.apk` and
`tv/build/outputs/apk/release/tv-release.apk`. They are signed with your debug key, so later builds from the same
computer install over them.

## Set up the phone

1. **Install the app:** `adb install -r server/build/outputs/apk/release/server-release.apk` with USB debugging on.
   Or copy the APK over and open it in the phone's file manager.
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
   With the cleanyourtv tools, use `tools/b64push.py` and then `pm install -r`.
2. **Pair:** open **HomeReel**, pick the phone from the list (or type its address), and enter the 6-digit code
   the phone shows.
3. **Optional:** add `com.parthajb.homereel.tv` to the launcher's dock.

Switch ADB off on the TV again when you're done.

## Adding and removing movies

| Way | How |
|---|---|
| Any browser on the Wi-Fi | Open `http://<phone ip>:8080/admin` and enter the pairing code once. Drag files in to upload them. Click a movie to edit its thumbnail, title, year, IMDb rating, genres and description, or to delete it. |
| USB cable | Plug the phone into a computer and copy files into the folder (on a Mac: OpenMTP or Android File Transfer). |
| On the phone | Download or move files into the folder with any file manager. |

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

## Private videos

Personal videos go in a folder called **Private** at the top of the movie folder. Use **🔒 Upload into the Private
folder** on the admin page, **Move to Private** on a film, or copy files there by cable.

- **Locked (the default):**
  - The TV doesn't list them, and asking for one directly gets "not found".
  - That covers the list, the details, the stream, the poster and saving progress.
- **Unlocking:** the admin page's **Private** bar unlocks them on the TV for 30 minutes to 4 hours. They then show
  in their own **🔒 Private** row on the TV and nowhere else.
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

## On the TV

- **Home screen:**
  - A full-screen backdrop and the focused title's details at the top: rating, year, age rating, length, genres,
    description.
  - Rows underneath: Continue watching (with progress bars), Recently added, Top rated, one row per genre, then
    everything A–Z, and Settings.
  - Back jumps to the top.
- **Details page:**
  - The tagline, cast and director.
  - Play, or Resume and Play from start, plus Mark as watched.
  - The video and audio formats, and why a file may not play.
  - **More like this**, from shared genres.
- **Player:**
  - −10 s and +30 s buttons.
  - **CC** cycles Off and each subtitle track, both `.srt` files and subtitles inside an MKV.
  - The **speaker** button cycles the audio tracks of a dubbed film.
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
  - The TV pairs as a **viewer**: it can list, stream and save progress.
  - A browser pairs as an **admin**: it can also upload, edit and delete. Its token is an `HttpOnly`,
    `SameSite=Strict` cookie, and every change also needs an `X-HomeReel` header that other websites can't send.
- **Folder access:** the phone can read and write only the folder you picked.

## API

All under `/api/v1`, JSON, with `Authorization: Bearer <token>` (or the admin cookie). The TV also sends
`X-Profile: <profile id>`. Progress, My List and kids filtering follow it, and without it the first profile is used.

| Method and path | Role | What it does |
|---|---|---|
| `POST /pair` `{code, client, role}` | none | Returns `{token, role, serverName}` |
| `GET /info` | viewer | Name, version, movie count, whether a scan is running |
| `GET /movies`, `GET /movies/{id}` | viewer | Films (not episodes) with tracks, subtitles, progress; private ones only while Private is unlocked |
| `GET /movies/{id}/stream` | viewer | The file, with `Range` support (`206`, `416`) |
| `GET /movies/{id}/poster`, `…/backdrop`, `…/subtitles/{n}` | viewer | Poster, wide backdrop, subtitle file |
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
| `GET` / `PUT /admin/private` `{unlock, minutes}` | admin | Whether Private is unlocked, and until when; unlocks or locks it |
| `POST /movies/{id}/move` `{private}` | admin | Moves a film into or out of the Private folder, keeping its details and resume point |
| `DELETE /movies/{id}` | admin | Deletes the file, its subtitles and poster |
| `GET /admin/status` | admin | Folder, free space, scan state, what can't play |
| `POST /admin/uploads` `{name, size, private}` | admin | Starts an upload (into Private if asked), or resumes one with the same name and size |
| `GET` / `PUT ?offset=N` / `DELETE /admin/uploads/{id}` | admin | Upload state, next piece, cancel |

## Troubleshooting

| Problem | What to do |
|---|---|
| The TV doesn't list the phone | Open HomeReel Server on the phone and check it says Running. Type the address it shows on the TV instead. |
| "Can't reach the phone" | The phone may be off Wi-Fi or asleep with battery optimisation on. Allow **Keep running in the background**. |
| A movie shows ⚠ | The TV has no decoder for that video or audio. DTS and TrueHD audio are common culprits. Convert it, for example `ffmpeg -i in.mkv -map 0 -c:v copy -c:a aac -b:a 384k -c:s copy out.mkv` for the audio. A soundbar or receiver over HDMI ARC may decode it instead. |
| Stutters in 4K | Wi-Fi bandwidth. Move the phone closer to the router or onto 5 GHz, or use a smaller copy. |
| No poster | The phone couldn't decode a frame. Add `Movie Name.jpg` beside the file. |
| "The app lost access to the folder" | Tap **Choose movie folder** and pick it again. |

## Layout

| Path | What it is |
|---|---|
| `shared/` | Plain Kotlin: API models, Range parsing, title parsing, the playability rules, and their unit tests |
| `server/` | Phone app: NanoHTTPD server, Storage Access Framework scanner, uploads, NSD, the admin page (`src/main/assets/admin.html`) |
| `tv/` | TV app: AndroidX Leanback and Media3 ExoPlayer |

# Changelog

Versions are the `versionName` of both apps, which are released together. Raise `versionCode` too, or the
update without a cable won't offer the new build.

## Unreleased

- **Checks on every pull request:** GitHub runs the unit tests, lint on both apps and both release builds on each
  pull request and on every push to `main` and `dev`.
- **Torrent search, safer redirects:** a download link that redirects to a name with any address on the home network
  is refused. Before, such a name was followed unless all of its addresses were on the home network.
- **Tests for the safety rules:** the torrent search rules (where requests may go, request limits, the cache) and
  the Private, kids and backup restore rules moved into `shared/`, each with tests.
- **A smaller phone app:** it's built as two copies, one for 64-bit phones (about 17 MB) and one for 32-bit phones
  (about 15 MB), instead of one 31 MB app for both. **Upload phone app…** refuses the copy for the other kind of
  phone and names the file it needs.
- **Clearer feed errors:** a search feed that fails says why in plain words: its name didn't resolve, it points
  nowhere (as blocked sites often do), it didn't answer, it refused the connection, its certificate didn't check out,
  or it had a problem of its own. A site on the internet that doesn't answer may be blocked by the internet provider;
  HomeReel says so, and doesn't work around it.
- **Subtitle timing:** subtitles that come too early or too late can be moved, in 0.1 s steps, from the TV's
  subtitles list or the web player's menu. The phone remembers it per video and serves the subtitle files shifted, so
  every TV and browser gets the same timing. Subtitles inside an MKV can't be moved.
- **Subtitle style on the TV:** a size, from Small to Extra large, and a background: an outline, a see-through box or
  a black box.
- **Finding subtitles (optional):** with a free OpenSubtitles key, the phone finds subtitles for films and episodes
  that have none, in the languages chosen in Settings → Movie info. It sends each file's hash, size and title, keeps
  what it finds in its own storage, and respects OpenSubtitles' daily limit. A film's editor can find them at once or
  remove them.
- **Night mode and volume boost on the TV:** the speaker button opens Sound, with night mode (loud parts quieter,
  voices clearer; Android 9 and later) and a volume boost of 3, 6 or 9 dB remembered per film or show.
- **Collections:** a film in a series (from TMDB, like "Harry Potter Collection") shows the rest of the series, in
  order, above More like this, on the TV and in the web app. The Genres page has a Collections tile for each series
  with two or more films here.
- **Play next on the TV's home screen:** on Android TV and Google TV, Continue watching also shows in the TV's own
  Play next row, updated while HomeReel is open and emptied for a kids profile.
- **The TV's own search and voice search:** HomeReel's titles show in the TV's search, and "Play … on HomeReel"
  plays the title (or a show's next episode), or opens HomeReel's search when more than one fits.
- **Home videos:** camera and phone clips, and anything marked Don't look up, get their own section on the TV and in
  the web app, grouped by the day they were recorded, newest first, and a row on Home. A camera clip is named after
  its date until renamed, and they stay out of the film rows.
- **Watching away from home:** a guide to Tailscale (docs/away-from-home.md), and the TV app now connects to a phone
  at a Tailscale address (100.64.0.0/10), for a TV stick away from home. It still refuses the internet.

## 0.6.2 (2026-10-26)

- **Several search feeds, searched together:** torrent search keeps a list of feeds instead of one indexer: Torznab
  sites, Prowlarr and Jackett, each with a name, on or off, Edit, Remove and Test connection. A search asks every feed
  that's on at once and merges the results: the same torrent shows once, most seeders first, labelled with who found
  it. A feed that is slow or fails is listed without spoiling the rest. Each feed has its own request limits.
- **Torznab feeds without a key:** the API key is optional for a Torznab feed, for sites whose Torznab search is open.
- The indexer set up in 0.6 becomes the first feed.

## 0.6.1 (2026-10-26)

- **Torrent search with Prowlarr or Jackett:** Settings → Downloads asks what to search with. Prowlarr uses its own
  search and Jackett its all-indexers feed, so one search asks every indexer set up in them. Results are sorted by
  seeders and name the indexer that found each one. **Test connection** checks the address and key and lists the
  indexers, without saving. Fixes searches that failed with Prowlarr or Jackett:
  - their main address was sent to a Torznab path they don't have;
  - results with only a download link were dropped;
  - download links that redirect to a magnet were refused;
  - searches across many indexers timed out after 12 seconds.

## 0.6.0 (2026-10-26)

- **Torrent search (optional):** with a Torznab indexer (Search Magnet Link, Jackett, Prowlarr…) and its key in
  Settings → Torrent downloads, a search box appears where you add movies. A result's Download button sends it to
  Downloads. Searches are rate-limited and cached; the key never leaves the phone except to the indexer.
- **Admin page, Settings:** split into sections (Movie info, Playback, Family, Private, Downloads, Backup, App
  updates) with a side menu, or tabs on a phone. Each section shows how it stands, and saves only its own settings.
- **Admin page, library:** sort by recently added, title, year, rating or recently watched; a compact List view
  beside the posters; Select mode to look up info, move into or out of Private, or delete several titles at once.
  The editor has tabs (Details, Online info, Playback, File or Episodes) instead of one long page.
- **TV and web app navigation:** a menu along the top with Home, Movies, TV shows, Genres, My List and (while it's
  unlocked) Private. Genres lists every genre; choosing one shows its top rated, movies and shows. On the TV, Back
  goes to the top, then to Home; in the web app each section has its own address.

## 0.5.0 (2026-10-26)

- **Torrent downloads (off by default):** drop a `.torrent` file or paste a magnet link on the admin page, and the
  phone downloads it in its own time: only while charging (by default), within chosen hours, with speed limits, and
  slowed while someone watches. Only videos and subtitles are kept; they go into the library (or Private) when done.
  Downloads on the main screen show progress, with Pause, Resume, Remove and Try again. Uses libtorrent4j; the phone
  app is about 30 MB now.

## 0.4.1 (2026-10-26)

- **TV:** the My List (heart) button on a film's page was squeezed into a thin sliver when Resume, Play from start and
  Mark as watched were all showing. The buttons now have the full width, on the show page too.
- **Admin page:** Private videos no longer have a tab, and stay out of All, the filters and search. Settings → Private
  folder → Open Private videos shows them.

## 0.4.0 (2026-10-25)

- **Web player:** HomeReel's own controls instead of the browser's: a seek bar with the time as you drag, ±10 s,
  volume, one menu for subtitles (and their size), audio and speed, Next episode, picture-in-picture, a loading
  spinner, double-tap to skip on phones, more keyboard shortcuts, and the lock screen's media controls. Each
  profile's subtitle choice is remembered.
- **TV player:** speed (1×, 1.25×, 1.5×, 2×); lists for subtitles and audio instead of cycling through them, remembered
  per profile; Left and Right skip 10 seconds while the controls are hidden; and "Ends at …" beside the title.
- **Admin page:** unlocking the Private folder moved into Settings. The main screen only shows a reminder while
  it's unlocked.

## 0.3.0 (2026-10-25)

- **Web app:** watch in any browser on the home Wi-Fi at `http://<phone ip>:8080`, on a laptop, phone or tablet,
  with profiles, the kids PIN, My List, search, shows, resume points, subtitle files, Skip intro and Next episode.
  The details page warns when a file may not play in that browser.

## 0.2.0 (2026-10-25)

- **Family:** profiles with their own progress, My List and recommendations; kids profiles that only see titles
  rated for children; a parent PIN to leave a kids profile.
- **Finding things:** search on the TV across titles, cast, directors and genres; My List; "Because you watched" rows.
- **Smooth playback:** lighter copies of very heavy files, made on the phone while it charges; a warning for files
  over 60 Mbps; frame-rate matching on the TV; Skip intro and an early "Next episode" from per-season markers.
- **Keeping it running:** a Health panel with warnings and an event log (crashes included); backup and restore of
  everything typed in or looked up; extra folders such as a USB drive, kept offline while unplugged.
- **Updates without a cable:** upload new APKs on the admin page; the TV and phone install them after a confirmation.

## 0.1.0 (2026-10-25)

- The phone server: one folder of videos, a REST API with Range streaming, pairing, and the admin page for
  uploads, editing, thumbnails and deleting.
- The TV app: a Netflix-style home screen, details page and player with resume points, audio and subtitle choice,
  and a check of each file against the TV's own decoders.
- Online movie info from TMDB (and IMDb ratings from OMDb) with your own free keys, with locked fields for anything
  you edit.
- TV series from file and folder names, with seasons, episode progress and autoplay of the next episode.
- A Private folder that the TV only shows while the admin page has it unlocked.

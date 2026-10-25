# Changelog

Versions are the `versionName` of both apps, which are released together. Raise `versionCode` too, or the
update without a cable won't offer the new build.

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

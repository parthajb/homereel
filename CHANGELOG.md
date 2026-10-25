# Changelog

Versions are the `versionName` of both apps, which are released together. Raise `versionCode` too, or the
update without a cable won't offer the new build.

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

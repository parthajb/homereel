# Roadmap

Where HomeReel is going, and what it won't do. This list is a plan, not a promise: items move when testing on a real
phone and TV says they should. Every item follows the principles in [CONTRIBUTING.md](CONTRIBUTING.md): it stays on
the home network, plays your own videos, runs on old hardware, and stays off until someone turns it on.

To suggest something, open a feature request. To work on an item, say so on its issue first.

## Where it stands (0.7, on `dev`)

0.1 to 0.6 built the whole loop: the phone server, the TV app, the web app and the admin page, plus online movie
info, TV shows, profiles with a kids PIN, lighter copies, Private videos, and torrent downloads with search feeds.

Since 0.6.2, on `dev`:
- **Trust and build:** checks on every pull request; the torrent, Private and backup rules tested in `shared/`; a
  phone app half the size, one APK per kind of phone; failed search feeds that say why.
- **On the sofa:** subtitle timing and style; finding subtitles with an OpenSubtitles key; night mode and a volume
  boost; collections; Continue watching in the TV home screen's Play next row; the TV's own search and "Play … on
  HomeReel".
- **Around the house:** a Home videos section by day; a guide to watching away from home with Tailscale, and the TV
  app able to reach the phone over it.
- **Towards 1.0:** the TV and the phone check each other's version and say which to update; backups carry their
  format, and one from a newer HomeReel is refused.

Tested on a Redmi Note 9 Pro (Android 13), a OnePlus TV (Android TV 11) and the emulator. The 0.7 work so far is
tested on the emulator only.

---

## Now: before 0.7 is released

| Item | What's left |
|---|---|
| **Try it on the real TV** | The Play next row and the TV's own search only exist on an Android TV, not the emulator. Also: night mode through the TV's speakers, and subtitle style at a sofa's distance. |
| **Screenshots** | The TV home, details, player, admin Settings and torrent search, for the README, with addresses and Wi-Fi names blurred. |
| **Tags** | Tag `v0.6.2` on `main`, then `v0.7.0` when `dev` is merged. GitHub release notes come from the changelog; there are no APKs, as people build from source. |
| **Release 0.7** | Raise the version, merge `dev` into `main`, and update the phone and TV. |

## Towards 1.0

1.0 means someone else can set HomeReel up from the README alone and keep it running for a year. Still to do:
- **Translations:** the TV app and phone app already keep every on-screen string in resources. The web app, the
  admin page and the phone's messages need a string catalogue first; then translations, Hindi first, if someone
  offers.
- **Restore tested from every earlier backup:** backups are versioned now; reading old backups in tests needs the
  backup's model moved into `shared/`.
- **Upgrades tested from each release** on a real phone and TV, not only the emulator.
- **Tested on more hardware:** at least one more phone and one more TV, ideally a 1 GB Android TV and a Google TV,
  with results in the README.
- **Watching away from home, tried:** the Tailscale guide hasn't been tried on a real trip.

Done for 1.0: a versioned API, a versioned backup format, checks on every pull request, and tests for every safety
rule.

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
| The phone as a remote (Play on TV) | Not needed: the TV's own remote, its search and voice search cover it. |

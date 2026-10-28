# Watching away from home

HomeReel only works on your home network. To watch from somewhere else, a laptop on a trip, a phone on mobile data,
or a TV stick in a holiday flat, join that device and the phone to the same private network with
[Tailscale](https://tailscale.com). Nothing is opened to the internet, and HomeReel needs no changes.

**Don't forward port 8080 on your router instead.** HomeReel speaks plain HTTP, and its pairing code is six digits:
anyone who found the port could try codes, and everything you watch would cross the internet unencrypted.

## What you need

- A free Tailscale account (a Google, Microsoft or GitHub sign-in works).
- The Tailscale app on the phone running HomeReel Server, and on each device you'll watch from. It's in the Play
  Store, including on Android TV and Google TV, and there are apps for Windows, macOS, iOS and Linux.
- The phone left at home, on Wi-Fi and charging, as usual.

## Set it up

1. **On the phone running HomeReel Server:** install Tailscale, sign in, and turn it on. Allow it to set up a VPN.
   - In Tailscale's settings, turn on **Always-on VPN** if your phone offers it (Android Settings → Network → VPN →
     Tailscale), so it comes back after a restart.
   - Leave **Keep running in the background** on for both HomeReel Server and Tailscale, so neither is stopped to
     save battery.
2. **Find the phone's Tailscale address:** in the Tailscale app it's listed under the phone's name, starting with
   `100.` (for example `100.101.102.103`). With MagicDNS on, the phone's name works too.
3. **Pair before you leave:** pairing needs the code on the phone's screen, so pair each browser or TV stick at home
   first, as usual (see [Watching in a browser](../README.md#watching-in-a-browser)). The pairing works over
   Tailscale afterwards.
4. **On the device you'll watch from:** install Tailscale, sign in with the same account, and turn it on.
   - **A browser:** open `http://100.101.102.103:8080` (the phone's Tailscale address). The web app, your profiles,
     My List and resume points are all there.
   - **A TV stick with the HomeReel TV app:** HomeReel can't find the phone by itself away from home. On the
     connect screen, under "Or type the address the phone shows", enter the phone's Tailscale address and port 8080.

## Things to know

- **Speed:** films come from your home broadband's *upload* speed, which is often much slower than its download
  speed. A 1080p film usually needs 5–10 Mbps. Heavy files stutter first; HomeReel's lighter copies help (Smooth
  playback in the README), and the web app plays a lighter copy when there is one.
- **Mobile data:** a film is 1–4 GB. Watch on Wi-Fi where you can.
- **Only your devices:** Tailscale only connects devices signed in to your account (or ones you share with on
  purpose), and the traffic between them is encrypted. HomeReel still asks for its pairing code, and for the PIN on
  kids profiles, as at home.
- **When something doesn't connect:** check Tailscale is on, on both ends, and that the phone shows as connected in
  the Tailscale app. If the phone was restarted, open HomeReel Server once.

This guide hasn't been tried end to end on a real trip yet. If you try it, say how it went in an issue.

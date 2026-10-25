# Security

HomeReel runs on a home network and handles people's own videos, so security problems matter even though nothing
is hosted on the internet.

## Reporting a problem

Report it privately: on GitHub, open the repository's **Security** tab and choose **Report a vulnerability**.
Please don't open a public issue for a security problem. Include the version, what an attacker needs (on the same
Wi-Fi? paired?), the steps, and what they gain.

You'll get an answer within a week. Fixes go into the next release, and the report is credited unless you'd rather
it wasn't.

Only the latest release gets fixes.

## What HomeReel protects, and what it doesn't

**In scope:**
- **Pairing:** anything that reaches the API without pairing, or gets round the 6-digit code's lockout.
- **Roles:** a viewer token (a TV or the web app) doing what only the admin page may do: uploading, editing, deleting, unlocking
  Private, uploading app updates.
- **Private and kids profiles:** a TV or the web app seeing private videos while Private is locked, or a kids
  profile getting titles, pictures or video it shouldn't.
- **The admin page and the web app:** cross-site requests, script injection through file names or looked-up
  details.
- **Files:** reading or writing outside the folders the person picked.
- **Updates:** getting the phone to keep, or a TV to install, an APK that isn't a HomeReel app signed with the same
  key.
- **Keys:** the TMDB or OMDb keys leaving the phone other than to TMDB and OMDb. The API only ever says whether
  they're set, even to the admin page.

**By design, and out of scope:**
- **Plain HTTP:** the API is plain HTTP on the home network. Anyone who can watch the Wi-Fi traffic can see what's
  streamed and capture a token. Don't run HomeReel on a network you don't trust, and don't expose port 8080 to the
  internet; use a VPN such as Tailscale to watch away from home.
- **The parent PIN** guards leaving a kids profile in the apps. It isn't a password: someone who can edit requests
  can name another profile.
- **The phone itself:** someone holding the unlocked phone can open its files. Keep a screen lock.
- **Your build key:** release builds are signed with the debug key of the computer that built them. Keep that
  computer's `~/.android/debug.keystore` to yourself: anyone with it can build an update your devices accept.

# IRL Game Maps — Privacy Policy

_Effective October 9, 2026_

IRL Game Maps ("the app") is a free, personal hobby project that shows your real location on game-styled maps and gives turn-by-turn directions on your phone and in Android Auto. It has no accounts, no ads, no analytics and no servers of its own. This policy explains exactly what data the app touches and where it goes.

## Data the app uses

### Your location
- **What:** precise and approximate location (GPS and network), including speed and direction of travel.
- **Why:** to show where you are on the map, keep the map pointed the way you're going, and give directions.
- **When:** while the app is open on your phone, and while it's open on your car screen through Android Auto. During Android Auto use the app runs a foreground service (with a visible notification) so directions keep working when your phone is locked. It does not track you in the background otherwise.
- **Stored?** No. Location is kept in memory only while the app is running and is never saved to storage or a server operated by the app.

### Compass / motion sensors
Used on your device only, to point your arrow the way your phone is facing. Never stored or sent anywhere.

### What you search for
The text you type or speak into search, and the destinations you choose, are used to find places and routes (see below). The app does not keep a search history.

### Settings
The map style you picked is saved on your device so it's remembered next time. Nothing else is stored.

## Third-party services the app talks to
To work, the app sends requests directly from your phone to these free public services. Like any website, they receive your device's IP address with each request.

| Service | What it receives | Why |
|---|---|---|
| **OpenFreeMap** (tiles.openfreemap.org) | Which map tiles to load (reveals the area you're viewing) | Drawing the map |
| **Photon by komoot** (photon.komoot.io) | Your search text and your approximate current position (to rank nearby results first) | Place search |
| **OSRM demo server** (router.project-osrm.org) | Your starting point (current location) and destination | Calculating driving routes |

These services are run by independent organizations under their own privacy policies:
- OpenFreeMap: https://openfreemap.org
- komoot (Photon): https://www.komoot.com/privacy
- Project OSRM: https://project-osrm.org

Spoken directions use your phone's built-in text-to-speech engine (for example Google Text-to-Speech), which may follow its own provider's privacy policy.

## What the app does not do
- No sign-in, accounts or user profiles.
- No advertising, analytics, crash reporting or tracking SDKs.
- No selling, renting or sharing of your data with anyone beyond the services listed above, and only for the purposes listed.
- No access to contacts, photos, files, microphone or camera. (Voice search in Android Auto is handled by Google Assistant, not by this app.)

## Your choices
- You can deny or revoke location permission at any time in Android Settings → Apps → IRL Game Maps → Permissions. The app will still show the map, but not your position or directions.
- Uninstalling the app removes the saved map-style setting, which is the only data it stores.

## Children
The app is not directed at children under 13 and does not knowingly collect information from them.

## Changes
If this policy changes, the updated version will be posted at this same address with a new effective date.

## Contact
Questions or requests: open an issue at https://github.com/kvn317/skill-assessment/issues

# IRL Game Maps

Your real location drawn in three game-map styles: **RDR2** (parchment), **Pip-Boy** (green terminal), and **Los Santos** (GTA V map). Works on the phone and on Android Auto.

## Get the APK
On your phone, open https://github.com/kvn317/skill-assessment/releases/latest/download/irl-game-maps.apk and install it (allow "install unknown apps"). Every push rebuilds it.

Open the app once on the phone and grant location.

## Android Auto needs the Google Play install
Android Auto only runs Car App Library apps (like this one) when they're installed from Google Play; "Unknown sources" doesn't cover them. CI builds a Play-signed release when the repository secrets `UPLOAD_KEYSTORE_B64` and `UPLOAD_KEYSTORE_PASSWORD` are set: grab `irl-game-maps-play.aab` (Internal testing) or `irl-game-maps-play.apk` (Internal app sharing) from the latest release and upload it in Play Console. Uninstall the sideloaded copy first (different signature).

## Android Auto developer settings
1. Android Auto settings → tap **Version** 10× to enable developer mode.
2. ⋮ → **Developer settings** → enable **Unknown sources**.
3. Reconnect to the car; "IRL Game Maps" appears in the launcher.

## Map
- Drag, fling, pinch or double-tap to zoom, two-finger tap to zoom out, twist to rotate, two-finger drag to tilt.
- Your arrow sits in a halo showing GPS accuracy; your speed shows bottom-right when moving.
- ◎ recenters and follows you; the style you pick is remembered (phone and car).

## Navigation
- **Phone:** search at the top (suggestions appear as you type, with distances), or long-press the map to drop a pin. The banner shows the next turn and ETA, with **Overview** (whole route), **🔊/🔇** (voice) and **End**. The map tilts into a 3D driving view while guiding. Back closes search results.
- **Car:** 🔍 to search (voice input works while driving), ✕ to end. Turn cards and ETA show in Android Auto, with spoken prompts that duck your music.
- **"Hey Google, navigate to …"** in Android Auto routes with this app once it's your active navigation app (open it on the car screen once; Android Auto remembers the last nav app you used).
- Tapping a map link or "Share → IRL Game Maps" on the phone (geo: / google.navigation: links) starts a route too.
- Reroutes automatically after ~3 seconds off route.

Your arrow points where the phone points when you're standing or walking, and follows your direction of travel when driving. The map turns with your direction of travel (it holds still below ~3 mph). On the phone, the **⬆ Heading / N North** button switches between heading-up and north-up; the car is always heading-up.

In the car, tap the theme name to cycle styles and +/− to zoom. The map follows you.

Map data: © OpenFreeMap © OpenMapTiles © OpenStreetMap contributors, drawn on-device with MapLibre. Search: Photon (komoot). Routing: OSRM public demo server (fair use, no uptime guarantee). Fonts (bundled as map glyphs via `tools/make_glyphs.py`): Rye, Share Tech Mono, Barlow Condensed (SIL Open Font License), Homemade Apple (Apache 2.0). Not affiliated with Rockstar or Bethesda.

package com.kvn317.gamemaps

/** One game look: every map feature gets its own color, so styles can match the games instead of recoloring a street map. */
class MapTheme(
    val name: String,
    val land: String,
    val water: String,
    val shore: String,
    val green: String,
    val building: String,
    val minor: String,
    val major: String,
    val motorway: String,
    val casing: String,
    val label: String,
    val halo: String,
    val font: String,
    val route: String,
    val blip: Int,
    /** HUD grid line color drawn over the map; 0 = none. */
    val grid: Int = 0,
)

object Themes {
    val all = listOf(
        // Red Dead Redemption 2 style: ink on parchment.
        MapTheme(
            "Saint Denis II",
            land = "#d8c39a", water = "#9cab9a", shore = "#5a4630", green = "#c7b688", building = "#c4ab7c",
            minor = "#8a6a48", major = "#6a4628", motorway = "#4a2e16", casing = "#d8c39a",
            label = "#3b2a1a", halo = "#e6d6b0", font = "Noto Sans Italic", route = "#b22222", blip = 0xFF8B1A1A.toInt(),
        ),
        // Fallout Pip-Boy, sampled from a reference screenshot.
        MapTheme(
            "Pip-Boy",
            land = "#1b3617", water = "#0f1a0c", shore = "#050c04", green = "#1d3a18", building = "#24451a",
            minor = "#2f6420", major = "#3a7a28", motorway = "#44902f", casing = "#0b1608",
            label = "#6fcf55", halo = "#0b1608", font = "Noto Sans Bold", route = "#c8ffb8", blip = 0xFFC8FFB8.toInt(),
            grid = 0x50346826,
        ),
        // GTA V's San Andreas pause map: slate land, white roads, navy water, purple GPS line.
        // ponytail: from memory of the game, not sampled; send a screenshot to tune it like Pip-Boy
        MapTheme(
            "San Andreas",
            land = "#5f6a74", water = "#2c4a63", shore = "#1d3345", green = "#55705a", building = "#77818a",
            minor = "#c8ccd0", major = "#e3e5e7", motorway = "#f7f7f7", casing = "#3c444c",
            label = "#ffffff", halo = "#1d2228", font = "Noto Sans Bold", route = "#b04fe0", blip = 0xFFFFFFFF.toInt(),
        ),
    )
}

/** Zoom-dependent line width. */
private fun width(vararg zw: Pair<Int, Double>) =
    """["interpolate",["exponential",1.5],["zoom"],${zw.joinToString(",") { "${it.first},${it.second}" }}]"""

private fun roads(vararg classes: String) =
    """["match",["get","class"],[${classes.joinToString(",") { "\"$it\"" }}],true,false]"""

/** MapLibre style over OpenFreeMap's free OpenMapTiles vector data (no API key). */
fun styleJson(t: MapTheme): String {
    val name = """["coalesce",["get","name:latin"],["get","name"]]"""
    val minorW = width(12 to 0.5, 14 to 1.5, 18 to 10.0)
    val majorW = width(9 to 0.6, 14 to 3.0, 18 to 16.0)
    val motorwayW = width(6 to 0.8, 14 to 4.0, 18 to 20.0)
    val casingW = width(9 to 1.6, 14 to 6.0, 18 to 24.0)
    return """
{
  "version": 8,
  "sources": {"omt": {"type": "vector", "url": "https://tiles.openfreemap.org/planet"}},
  "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
  "layers": [
    {"id": "land", "type": "background", "paint": {"background-color": "${t.land}"}},
    {"id": "landcover", "type": "fill", "source": "omt", "source-layer": "landcover",
     "filter": ${roads("wood", "grass", "farmland")}, "paint": {"fill-color": "${t.green}", "fill-opacity": 0.6}},
    {"id": "park", "type": "fill", "source": "omt", "source-layer": "park", "paint": {"fill-color": "${t.green}"}},
    {"id": "water", "type": "fill", "source": "omt", "source-layer": "water", "paint": {"fill-color": "${t.water}"}},
    {"id": "shore", "type": "line", "source": "omt", "source-layer": "water", "paint": {"line-color": "${t.shore}", "line-width": 1.5}},
    {"id": "waterway", "type": "line", "source": "omt", "source-layer": "waterway", "paint": {"line-color": "${t.water}", "line-width": 2}},
    {"id": "building", "type": "fill", "source": "omt", "source-layer": "building", "minzoom": 13,
     "paint": {"fill-color": "${t.building}", "fill-outline-color": "${t.shore}"}},
    {"id": "minor", "type": "line", "source": "omt", "source-layer": "transportation",
     "filter": ${roads("minor", "service", "tertiary")},
     "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "${t.minor}", "line-width": $minorW}},
    {"id": "casing", "type": "line", "source": "omt", "source-layer": "transportation",
     "filter": ${roads("motorway", "trunk", "primary", "secondary")},
     "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "${t.casing}", "line-width": $casingW}},
    {"id": "major", "type": "line", "source": "omt", "source-layer": "transportation",
     "filter": ${roads("trunk", "primary", "secondary")},
     "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "${t.major}", "line-width": $majorW}},
    {"id": "motorway", "type": "line", "source": "omt", "source-layer": "transportation",
     "filter": ${roads("motorway")},
     "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "${t.motorway}", "line-width": $motorwayW}},
    {"id": "road-names", "type": "symbol", "source": "omt", "source-layer": "transportation_name", "minzoom": 13,
     "layout": {"symbol-placement": "line", "text-field": $name, "text-font": ["${t.font}"], "text-size": 12,
                "text-transform": "uppercase", "text-letter-spacing": 0.05},
     "paint": {"text-color": "${t.label}", "text-halo-color": "${t.halo}", "text-halo-width": 1.5}},
    {"id": "places", "type": "symbol", "source": "omt", "source-layer": "place",
     "filter": ${roads("city", "town", "village", "suburb", "neighbourhood")},
     "layout": {"text-field": $name, "text-font": ["${t.font}"], "text-transform": "uppercase", "text-letter-spacing": 0.1,
                "text-size": ["match", ["get", "class"], "city", 18, "town", 15, 12]},
     "paint": {"text-color": "${t.label}", "text-halo-color": "${t.halo}", "text-halo-width": 2}}
  ]
}
"""
}

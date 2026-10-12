package com.syconx.us2us.feature.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.syconx.us2us.ui.env.ExternalActivityGuard
import java.util.Locale

// 5.2 › “Haritada aç”: a place (or a live share's latest position) handed to a maps app on this
// device or to Google Maps. Nothing leaves the app until the user picks one, and then only the
// coordinates — plus the place's name for the maps app, when the sender gave one.

/** A point handed to a maps app. [name] is the sender's name for it; empty when there is none (a live share's position). */
data class MapPlace(val name: String, val latitude: Double, val longitude: Double)

enum class MapApp {
    /** A maps app on this device (`geo:`), or — without one — OpenStreetMap in the browser. */
    Device,

    /** The Google Maps app when it is installed, otherwise google.com/maps in the browser. */
    Google,
}

/**
 * The floating chooser's content: what would be handed over, whether a maps app and the Google Maps
 * app are installed, and whether the place comes from a disappearing message (the chooser then says
 * that the maps app may keep it in its own history).
 */
data class MapChoice(
    val place: MapPlace,
    val hasMapsApp: Boolean,
    val hasGoogleMapsApp: Boolean,
    val isDisappearing: Boolean = false,
)

/** A choice made in the chooser, carried out once the sheet is down. */
data class MapLaunch(val app: MapApp, val place: MapPlace)

/** The links that open a point in a maps app. Pure: no app is queried here. */
object MapLinks {
    /** The Google Maps app's package. */
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

    /** Unreserved characters (RFC 3986) and the comma of “lat,lng” stay; everything else is percent-encoded as UTF-8. */
    private const val QUERY_VALUE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~,"

    /** One coordinate with six decimals (about 0.1 m) and a “.”, whatever the language. */
    fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

    /** “40.984600,29.024900”. */
    fun coordinatePair(place: MapPlace): String = coordinate(place.latitude) + "," + coordinate(place.longitude)

    /** “40.98460, 29.02490” — what the chooser shows will be handed over. */
    fun displayCoordinates(place: MapPlace): String = String.format(Locale.ROOT, "%.5f, %.5f", place.latitude, place.longitude)

    /**
     * `geo:` for a maps app on this device: the pin at the point and, when there is one, the
     * place's name as its label (`geo:lat,lng?q=lat,lng(Moda%20%C4%B0skelesi)`).
     */
    fun geo(place: MapPlace): String {
        val pair = coordinatePair(place)
        val name = place.name.trim()
        val query = if (name.isEmpty()) pair else "$pair(${encode(name.replace("(", " ").replace(")", " "))})"
        return "geo:$pair?q=$query"
    }

    /** Google's documented Maps URL (`api=1`): www.google.com/maps/search/ — the Google Maps app opens it too. */
    fun googleMaps(place: MapPlace): String = "https://www.google.com/maps/search/?" + query(listOf("api" to "1", "query" to coordinatePair(place)))

    /** OpenStreetMap with a marker at the point, at street level. */
    fun openStreetMap(place: MapPlace): String {
        val lat = coordinate(place.latitude)
        val lng = coordinate(place.longitude)
        return "https://www.openstreetmap.org/?mlat=$lat&mlon=$lng#map=17/$lat/$lng"
    }

    /** `name=value&…`, the values percent-encoded. */
    private fun query(items: List<Pair<String, String>>): String = items.joinToString("&") { (name, value) -> name + "=" + encode(value) }

    fun encode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val char = (byte.toInt() and 0xFF).toChar()
            if (char in QUERY_VALUE_CHARACTERS) {
                append(char)
            } else {
                append('%').append(String.format(Locale.ROOT, "%02X", byte.toInt() and 0xFF))
            }
        }
    }
}

/** Opens a point in the chosen maps app (under the app's guard: returning does not lock). */
object MapLauncher {
    /** Whether a maps app takes `geo:` links. */
    fun hasMapsApp(context: Context): Boolean =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=0,0")).resolveActivity(context.packageManager) != null

    /** Whether the Google Maps app is installed (only this package is ever queried). */
    fun hasGoogleMapsApp(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(MapLinks.GOOGLE_MAPS_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun choice(context: Context, place: MapPlace, isDisappearing: Boolean): MapChoice =
        MapChoice(place, hasMapsApp = hasMapsApp(context), hasGoogleMapsApp = hasGoogleMapsApp(context), isDisappearing = isDisappearing)

    fun open(context: Context, guard: ExternalActivityGuard, place: MapPlace, app: MapApp) {
        val web: () -> Unit = {
            val url = if (app == MapApp.Google) MapLinks.googleMaps(place) else MapLinks.openStreetMap(place)
            start(context, guard, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        when (app) {
            MapApp.Device -> if (!start(context, guard, Intent(Intent.ACTION_VIEW, Uri.parse(MapLinks.geo(place))))) web()
            MapApp.Google -> {
                val inApp = Intent(Intent.ACTION_VIEW, Uri.parse(MapLinks.googleMaps(place))).setPackage(MapLinks.GOOGLE_MAPS_PACKAGE)
                // The app could not take it after all: the web page instead.
                if (!hasGoogleMapsApp(context) || !start(context, guard, inApp)) web()
            }
        }
    }

    private fun start(context: Context, guard: ExternalActivityGuard, intent: Intent): Boolean {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            guard.launching { context.startActivity(intent) }
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

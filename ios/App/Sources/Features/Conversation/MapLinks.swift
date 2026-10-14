import Foundation
import UIKit

// 5.2 › “Haritada aç”: a place (or a live share's latest position) handed to
// Apple Maps or Google Maps. Nothing leaves the app until the user picks
// one of the two, and then only the coordinates — plus the place's name for
// Apple Maps, when the sender gave one.

/// A point handed to a maps app.
struct MapPlace: Hashable, Sendable {
    /// The sender's name for the place; empty when there is none (a live
    /// share's position never carries a name).
    let name: String
    let latitude: Double
    let longitude: Double
}

enum MapApp: String, CaseIterable, Sendable {
    case apple
    case google
}

/// The floating chooser's content: what would be handed over, whether
/// Google Maps opens as the app or in the browser, and whether the place
/// comes from a disappearing message (the chooser then says that the maps
/// app may keep it in its own history).
struct MapChoice: Identifiable, Equatable {
    let id = UUID()
    let place: MapPlace
    let hasGoogleMapsApp: Bool
    var isDisappearing = false
}

/// A choice made in the chooser, carried out once the sheet is down.
struct MapLaunch: Equatable {
    let app: MapApp
    let place: MapPlace
}

/// The links that open a point in a maps app. Pure: no app is queried here.
enum MapLinks {
    /// Google Maps' URL scheme (Info.plist › LSApplicationQueriesSchemes).
    static let googleMapsScheme = "comgooglemaps"

    /// Unreserved characters (RFC 3986) and the comma of “lat,lng” stay
    /// as they are; everything else — spaces, “+”, “&”, Turkish letters —
    /// is percent-encoded as UTF-8.
    private static let queryValueCharacters = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~,")

    /// One coordinate with six decimals (about 0.1 m) and a “.”, whatever
    /// the language — Turkish writes decimals with a comma, URLs never do.
    static func coordinate(_ value: Double) -> String {
        String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), value)
    }

    /// “40.984600,29.024900”.
    static func coordinatePair(_ place: MapPlace) -> String {
        coordinate(place.latitude) + "," + coordinate(place.longitude)
    }

    /// “40.98460, 29.02490” — what the chooser shows will be handed over.
    static func displayCoordinates(_ place: MapPlace) -> String {
        let posix = Locale(identifier: "en_US_POSIX")
        return String(format: "%.5f, %.5f", locale: posix, place.latitude, place.longitude)
    }

    /// The link for `app`. Google Maps opens as the app through its scheme
    /// when it is installed, otherwise as google.com/maps in the browser.
    static func url(for app: MapApp, place: MapPlace, googleMapsAppInstalled: Bool) -> URL? {
        switch app {
        case .apple: appleMaps(place)
        case .google: googleMapsAppInstalled ? googleMapsApp(place) : googleMapsWeb(place)
        }
    }

    /// maps.apple.com with `ll` (the pin) and, when there is one, the
    /// place's name as `q` (the pin's label). The Maps app opens it.
    static func appleMaps(_ place: MapPlace) -> URL? {
        var items = [("ll", coordinatePair(place))]
        let name = place.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !name.isEmpty { items.append(("q", name)) }
        return link(scheme: "https", host: "maps.apple.com", path: "/", query: items)
    }

    /// The Google Maps app (`comgooglemaps://`): a pin at the point, the map centred on it.
    static func googleMapsApp(_ place: MapPlace) -> URL? {
        let pair = coordinatePair(place)
        return URL(string: googleMapsScheme + "://?" + query([("q", pair), ("center", pair), ("zoom", "16")]))
    }

    /// Google's documented Maps URL (`api=1`): www.google.com/maps/search/.
    static func googleMapsWeb(_ place: MapPlace) -> URL? {
        link(
            scheme: "https", host: "www.google.com", path: "/maps/search/",
            query: [("api", "1"), ("query", coordinatePair(place))])
    }

    private static func link(scheme: String, host: String, path: String, query items: [(String, String)]) -> URL? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = host
        components.path = path
        components.percentEncodedQuery = query(items)
        return components.url
    }

    /// `name=value&…`, the values percent-encoded.
    private static func query(_ items: [(String, String)]) -> String {
        items.map { name, value in
            name + "=" + (value.addingPercentEncoding(withAllowedCharacters: queryValueCharacters) ?? "")
        }
        .joined(separator: "&")
    }
}

/// Opens a point in the chosen maps app.
@MainActor
enum MapLauncher {
    /// Whether the Google Maps app is installed (only this scheme is ever queried).
    static var hasGoogleMapsApp: Bool {
        guard let url = URL(string: MapLinks.googleMapsScheme + "://") else { return false }
        return UIApplication.shared.canOpenURL(url)
    }

    static func open(_ place: MapPlace, in app: MapApp) {
        switch app {
        case .apple:
            guard let url = MapLinks.appleMaps(place) else { return }
            UIApplication.shared.open(url)
        case .google:
            let web = MapLinks.googleMapsWeb(place)
            guard hasGoogleMapsApp, let url = MapLinks.googleMapsApp(place) else {
                if let web { UIApplication.shared.open(web) }
                return
            }
            UIApplication.shared.open(url, options: [:]) { opened in
                // The app could not take it after all: the web page instead.
                if !opened, let web { UIApplication.shared.open(web) }
            }
        }
    }
}

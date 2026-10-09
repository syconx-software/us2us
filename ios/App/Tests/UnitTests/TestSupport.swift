import SwiftUI
import UIKit
import XCTest

@testable import us2us

/// The ios/design folder (spec), located from this file.
enum DesignSpec {
    static var directory: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // UnitTests
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // App
            .deletingLastPathComponent()  // ios
            .appendingPathComponent("design")
    }

    static func json(_ name: String) throws -> [String: Any] {
        let data = try Data(contentsOf: directory.appendingPathComponent(name))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    /// The app's string catalog.
    static func catalog(_ name: String = "Localizable.xcstrings") throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Resources").appendingPathComponent(name)
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
}

/// Lays a SwiftUI view out like the app does (Turkish, dark, frozen clock,
/// default Dynamic Type) and returns its size.
@MainActor
func layoutSize<V: View>(_ view: V, width: CGFloat? = nil, locale: String = "tr") -> CGSize {
    U2Fonts.registerBundledFonts()
    let root = view
        .environment(\.locale, Locale(identifier: locale))
        .environment(\.u2Clock, .frozen(at: DesignFixtures.referenceDate))
        .environment(\.dynamicTypeSize, .large)
        .environment(\.colorScheme, .dark)
    let host = UIHostingController(rootView: root)
    host.view.backgroundColor = .clear
    let size = host.sizeThatFits(in: CGSize(width: width ?? CGFloat.greatestFiniteMagnitude, height: .greatestFiniteMagnitude))
    return size
}

/// Asserts that two design measurements agree within `tolerance` points.
func XCTAssertMeasure(
    _ actual: CGFloat, _ expected: CGFloat, tolerance: CGFloat = 0.5, _ message: String = "", file: StaticString = #filePath,
    line: UInt = #line
) {
    XCTAssertEqual(actual, expected, accuracy: tolerance, message, file: file, line: line)
}

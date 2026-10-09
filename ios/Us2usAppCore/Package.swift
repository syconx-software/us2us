// swift-tools-version:6.0
//
// Us2usAppCore — UI-free app core for the us2us iOS app, built on ../Us2usKit.
//
//   Us2usStorage  encrypted local storage (PROTOCOL.md §5.3): SQLCipher database,
//                 per-conversation key slots, encrypted items, crypto-shredding,
//                 ghost / disappearing deadlines, persistent protocol stores.
//   Us2usRuntime  the app runtime (connection lifecycle, send/receive, scheduling)
//                 on top of Us2usKit and Us2usStorage.
//
// Like Us2usKit (libsignal has no macOS slice) this package builds and tests on
// iOS only:
//
//   xcodebuild test -scheme Us2usAppCore-Package \
//     -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
//     [-only-testing:Us2usStorageTests]
//
// Third-party dependency (pinned exactly):
//   SQLCipher.swift 4.19.0 — Zetetic's official Swift package for SQLCipher
//   (SQLite 3.x + AES-256 page encryption with HMAC). A binary XCFramework
//   whose SHA-256 checksum is pinned in that package's manifest, so SwiftPM
//   verifies the downloaded artifact. It is the supported SwiftPM route in
//   2026: GRDB 7 still needs a hand-edited fork for SQLCipher under SwiftPM,
//   and Apple ships no encrypted SQLite. Us2usStorage therefore uses its own
//   small wrapper over the C API. BSD-style licence: the app must show
//   SQLCipher's licence in its acknowledgements.

import PackageDescription

let package = Package(
    name: "Us2usAppCore",
    defaultLocalization: "en",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "Us2usStorage", targets: ["Us2usStorage"]),
        .library(name: "Us2usRuntime", targets: ["Us2usRuntime"]),
    ],
    dependencies: [
        .package(path: "../Us2usKit"),
        .package(url: "https://github.com/sqlcipher/SQLCipher.swift.git", exact: "4.19.0"),
        // Already a dependency of Us2usKit; keep this pin equal to the version
        // Us2usKit resolves. Declared because Us2usStorage serializes brp.proto
        // messages itself.
        .package(url: "https://github.com/apple/swift-protobuf.git", exact: "1.38.1"),
    ],
    targets: [
        // One-function shim: sqlite3_key() is declared only under
        // SQLITE_HAS_CODEC, which Swift cannot define for an imported header.
        // (Objective-C because SQLCipher's umbrella header imports Foundation.)
        .target(
            name: "Us2usSQLCipherKey",
            dependencies: [.product(name: "SQLCipher", package: "SQLCipher.swift")],
            cSettings: [.define("SQLITE_HAS_CODEC")]
        ),
        .target(
            name: "Us2usStorage",
            dependencies: [
                "Us2usSQLCipherKey",
                .product(name: "Us2usKit", package: "Us2usKit"),
                .product(name: "SQLCipher", package: "SQLCipher.swift"),
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
            ]
        ),
        .target(
            name: "Us2usRuntime",
            dependencies: [
                "Us2usStorage",
                .product(name: "Us2usKit", package: "Us2usKit"),
            ]
        ),
        .testTarget(
            name: "Us2usStorageTests",
            dependencies: [
                "Us2usStorage",
                .product(name: "Us2usKit", package: "Us2usKit"),
                .product(name: "SQLCipher", package: "SQLCipher.swift"),
            ]
        ),
        .testTarget(
            name: "Us2usRuntimeTests",
            dependencies: [
                "Us2usRuntime",
                "Us2usStorage",
                .product(name: "Us2usKit", package: "Us2usKit"),
            ]
        ),
    ],
    swiftLanguageModes: [.v6]
)

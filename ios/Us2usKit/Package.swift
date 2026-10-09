// swift-tools-version:6.0
//
// Us2usKit — BRP protocol and crypto for the us2us iOS app.
//
// libsignal is vendored by ../scripts/fetch-libsignal.sh into ../Vendor, which
// is reachable from here through the committed `Vendor` symlink (SwiftPM does
// not allow target paths outside the package root). There is no macOS slice of
// libsignal_ffi, so this package builds and tests on iOS only:
//
//   xcodebuild test -scheme Us2usKit -destination 'platform=iOS Simulator,name=iPhone 17'

import PackageDescription

let package = Package(
    name: "Us2usKit",
    defaultLocalization: "en",
    platforms: [.iOS(.v15)],
    // A single product keeps the auto-generated `Us2usKit` scheme testable.
    // An app that also ships extensions wraps this library in one embedded
    // framework target of its own (see README, "Consuming the kit").
    products: [
        .library(name: "Us2usKit", targets: ["Us2usKit"])
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.38.1")
    ],
    targets: [
        .binaryTarget(name: "SignalFfi", path: "Vendor/SignalFfi.xcframework"),
        .target(
            name: "LibSignalClient",
            dependencies: ["SignalFfi"],
            path: "Vendor/LibSignalClient",
            swiftSettings: [.enableExperimentalFeature("StrictConcurrency")]
        ),
        .target(
            name: "Us2usKit",
            dependencies: [
                "LibSignalClient",
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
            ],
            resources: [
                .process("Resources/Localizable.xcstrings"),
                .copy("Resources/common-secret-codes.txt"),
                .copy("Resources/common-backup-passwords.txt"),
            ]
        ),
        .testTarget(
            name: "Us2usKitTests",
            dependencies: ["Us2usKit"]
        ),
    ]
)

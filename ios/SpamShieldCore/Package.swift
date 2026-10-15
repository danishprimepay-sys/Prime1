// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "SpamShieldCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "SpamShieldCore", targets: ["SpamShieldCore"]),
    ],
    targets: [
        .target(
            name: "SpamShieldCore",
            resources: [
                // Copied from shared/ by scripts/sync-shared.sh; do not edit here.
                .copy("Resources/rules.json"),
                .copy("Resources/sms_classifier.json"),
            ]
        ),
        .testTarget(
            name: "SpamShieldCoreTests",
            dependencies: ["SpamShieldCore"]
        ),
    ]
)

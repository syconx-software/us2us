#!/usr/bin/env swift
//
// Renders the 1024×1024 app icon from the design's app-icon mock (inventory 3.4,
// element r.2.0): a 30×30 tile with radial-gradient(90% 90% at 30% 15%,
// #423a6a → #161826 72%) and the line mark (two Ø6 rings, 1.5 stroke, 8×1.5 bar,
// 20 wide, #b5abfc) centred. iOS applies the corner mask itself, so the tile is
// rendered full-bleed.
//
//   swift ios/App/scripts/render-app-icon.swift
//
// Writes Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png.

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let size = 1024.0
let scale = size / 30.0

func rgb(_ hex: UInt32, _ alpha: CGFloat = 1) -> CGColor {
    CGColor(
        srgbRed: CGFloat((hex >> 16) & 0xff) / 255,
        green: CGFloat((hex >> 8) & 0xff) / 255,
        blue: CGFloat(hex & 0xff) / 255,
        alpha: alpha)
}

let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
guard
    let ctx = CGContext(
        data: nil, width: Int(size), height: Int(size), bitsPerComponent: 8, bytesPerRow: 0,
        space: colorSpace, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
else { fatalError("no context") }

// CoreGraphics' origin is bottom-left; flip to the CSS coordinate space.
ctx.translateBy(x: 0, y: size)
ctx.scaleBy(x: 1, y: -1)

// Background: the gradient's end colour fills everything beyond 72 %.
ctx.setFillColor(rgb(0x161826))
ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))

// radial-gradient(90% 90% at 30% 15%, #423a6a, #161826 72%): an ellipse with
// radii 90 % of the box, centred at (30 %, 15 %).
let gradient = CGGradient(
    colorsSpace: colorSpace, colors: [rgb(0x423a6a), rgb(0x161826)] as CFArray,
    locations: [0, 0.72])!
let center = CGPoint(x: 0.30 * size, y: 0.15 * size)
let radius = 0.90 * size
ctx.drawRadialGradient(
    gradient, startCenter: center, startRadius: 0, endCenter: center, endRadius: radius,
    options: [.drawsAfterEndLocation])

// Line mark: rings at x 5…11 and 19…25 (Ø6 incl. the 1.5 border), bar 11…19, y centre 15.
let ink = rgb(0xb5abfc)
ctx.setStrokeColor(ink)
ctx.setFillColor(ink)
let stroke = 1.5 * scale
ctx.setLineWidth(stroke)
for x in [5.0, 19.0] {
    let ringRect = CGRect(x: x * scale, y: 12 * scale, width: 6 * scale, height: 6 * scale)
    ctx.strokeEllipse(in: ringRect.insetBy(dx: stroke / 2, dy: stroke / 2))
}
ctx.fill(CGRect(x: 11 * scale, y: (15 - 0.75) * scale, width: 8 * scale, height: 1.5 * scale))

guard let image = ctx.makeImage() else { fatalError("no image") }
let scriptURL = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL
let appDir = scriptURL.deletingLastPathComponent().deletingLastPathComponent()
let out = appDir.appendingPathComponent("Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png")
try FileManager.default.createDirectory(
    at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
guard let dest = CGImageDestinationCreateWithURL(out as CFURL, UTType.png.identifier as CFString, 1, nil)
else { fatalError("no destination") }
CGImageDestinationAddImage(dest, image, nil)
guard CGImageDestinationFinalize(dest) else { fatalError("write failed") }
print("wrote \(out.path)")

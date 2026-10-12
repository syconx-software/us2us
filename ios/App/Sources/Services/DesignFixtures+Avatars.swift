import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers
import Us2usRuntime

// Profile pictures of the demo data (proposed — the design draws only
// initials): four chats show a picture (three presets, one photo), a few
// usernames have one in search, and two request senders have one behind
// “Profil resmini göster”. The rows the design draws in its references (deniz,
// mert_k, ayla_) and the own account keep their initials.
extension DesignFixtures {
    /// The demo photo: a small sunset over the sea, painted here and made
    /// into a profile photo by the same pipeline as a picked one (256 × 256,
    /// no metadata). In memory only.
    static let photoAvatar: AvatarPicture = {
        guard let source = paintedSunsetJPEG() else { return .initials }
        if let photo = try? AvatarPhotoProcessor.prepare(source) {
            return .photo(AvatarPhotoData(data: photo.data, contentType: photo.format.contentType))
        }
        return .photo(AvatarPhotoData(data: source, contentType: "image/jpeg"))
    }()

    /// The pictures of the 4.1 rows below the design's first three.
    static func chatAvatar(for username: String) -> AvatarPicture {
        switch username {
        case "kaan": .preset(2)
        case "ece_y": .preset(5)
        case "bora": .preset(16)
        case "oya_t": photoAvatar
        default: .initials
        }
    }

    /// What 4.2 finds in search: the contacts' own pictures, and a preset
    /// for “lale” (no chat yet). “deniz” as 4.2 draws her: the initial.
    static func searchAvatar(for username: String) -> AvatarPicture {
        switch username {
        case "lale": .preset(14)
        case "kuzey_dev": requestAvatar(for: username)
        default: chatAvatar(for: username)
        }
    }

    /// What “Profil resmini göster” finds for a request's sender (4.4): kuzey_dev
    /// has a preset, n_aksoy none (the initial stays and 4.4 says so).
    static func requestAvatar(for sender: String) -> AvatarPicture {
        switch sender {
        case "kuzey_dev": .preset(21)
        default: .initials
        }
    }

    /// 256 × 256: a dusk sky, the sun low over the sea, its path of light
    /// and a headland — a JPEG with nothing but the pixels.
    private static func paintedSunsetJPEG() -> Data? {
        let side = 256
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
            let context = CGContext(
                data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return nil }
        func color(_ hex: UInt32, _ alpha: CGFloat = 1) -> CGColor {
            CGColor(
                srgbRed: CGFloat((hex >> 16) & 0xff) / 255, green: CGFloat((hex >> 8) & 0xff) / 255,
                blue: CGFloat(hex & 0xff) / 255, alpha: alpha)
        }
        let size = CGFloat(side)
        // Core Graphics draws from the bottom up: the sea is low y.
        let horizon = size * 0.36
        if let sky = CGGradient(
            colorsSpace: space, colors: [color(0xf7b27e), color(0xd9707a), color(0x5b4a8f)] as CFArray,
            locations: [0, 0.45, 1])
        {
            context.drawLinearGradient(
                sky, start: CGPoint(x: 0, y: horizon), end: CGPoint(x: 0, y: size), options: [.drawsAfterEndLocation])
        }
        let sun = CGPoint(x: size * 0.58, y: horizon + size * 0.1)
        if let glow = CGGradient(
            colorsSpace: space, colors: [color(0xffe2a8, 0.9), color(0xffc58a, 0)] as CFArray, locations: [0, 1])
        {
            context.drawRadialGradient(
                glow, startCenter: sun, startRadius: 0, endCenter: sun, endRadius: size * 0.34, options: [])
        }
        context.setFillColor(color(0xfff0c9))
        context.fillEllipse(in: CGRect(x: sun.x - 26, y: sun.y - 26, width: 52, height: 52))
        if let sea = CGGradient(
            colorsSpace: space, colors: [color(0x3a3f74), color(0x171a37)] as CFArray, locations: [0, 1])
        {
            context.saveGState()
            context.clip(to: CGRect(x: 0, y: 0, width: size, height: horizon))
            context.drawLinearGradient(
                sea, start: CGPoint(x: 0, y: horizon), end: CGPoint(x: 0, y: 0), options: [])
            context.restoreGState()
        }
        // The sun's path on the water.
        context.setFillColor(color(0xffd9a0, 0.75))
        for row in 0..<9 {
            let y = horizon - 8 - CGFloat(row) * 9
            let width = 34 - CGFloat(row) * 2.4 + CGFloat(row % 3) * 6
            context.fill(CGRect(x: sun.x - width / 2 + CGFloat(row % 2) * 4, y: y, width: width, height: 2.2))
        }
        // A headland on the left.
        context.setFillColor(color(0x231d3d))
        context.beginPath()
        context.move(to: CGPoint(x: 0, y: horizon - 2))
        context.addLine(to: CGPoint(x: 0, y: horizon + size * 0.2))
        context.addCurve(
            to: CGPoint(x: size * 0.42, y: horizon - 2), control1: CGPoint(x: size * 0.14, y: horizon + size * 0.2),
            control2: CGPoint(x: size * 0.24, y: horizon + size * 0.02))
        context.closePath()
        context.fillPath()
        guard let image = context.makeImage() else { return nil }
        let output = NSMutableData()
        guard
            let destination = CGImageDestinationCreateWithData(
                output as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(
            destination, image, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return output as Data
    }
}

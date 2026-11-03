#!/usr/bin/env swift

import AppKit
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// Generates the Android debug launcher icons from the production assets.
// Each debug icon is the production icon with a red "DEBUG" corner banner.
let debugBannerFillColor = CGColor(
    red: 183.0 / 255.0,
    green: 28.0 / 255.0,
    blue: 28.0 / 255.0,
    alpha: 1.0,
)

let densities = ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]
let iconNames = ["ic_launcher.png", "ic_launcher_round.png", "ic_launcher_foreground.png"]

func loadImage(_ path: String) -> CGImage {
    let url = URL(fileURLWithPath: path)
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
        fatalError("could not load image at \(path)")
    }
    return image
}

func writePNG(_ image: CGImage, to path: String) {
    let url = URL(fileURLWithPath: path)
    let type = UTType.png.identifier as CFString
    guard let dest = CGImageDestinationCreateWithURL(url as CFURL, type, 1, nil) else {
        fatalError("could not create destination for \(path)")
    }
    CGImageDestinationAddImage(dest, image, nil)
    if !CGImageDestinationFinalize(dest) {
        fatalError("could not finalize \(path)")
    }
}

/// Draws a Flutter-style diagonal banner in the top-left corner.
func drawDebugBanner(context: CGContext, canvas: CGFloat) {
    context.saveGState()
    context.translateBy(x: 0, y: canvas)
    context.scaleBy(x: 1, y: -1)

    let ribbonHeight = max(3, canvas * (12.0 / 96.0))
    let offset = ribbonHeight * (40.0 / 12.0)
    let ribbon = CGRect(
        x: -offset,
        y: offset - ribbonHeight,
        width: offset * 2,
        height: ribbonHeight,
    )

    context.rotate(by: -.pi / 4)

    if canvas >= 32 {
        context.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 0.28))
        context.fill(ribbon.offsetBy(dx: 0.8, dy: 0.8))
    }

    context.setFillColor(debugBannerFillColor)
    context.fill(ribbon)

    if canvas >= 20 {
        let fontSize = max(4, ribbonHeight * 0.58)
        let text = NSAttributedString(
            string: "DEBUG",
            attributes: [
                .font: NSFont.boldSystemFont(ofSize: fontSize),
                .foregroundColor: NSColor.white,
            ],
        )
        let textSize = text.size()
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: true)
        text.draw(
            at: CGPoint(
                x: -textSize.width / 2,
                y: ribbon.minY + (ribbon.height - textSize.height) / 2,
            ),
        )
        NSGraphicsContext.restoreGraphicsState()
    }

    context.restoreGState()
}

func renderDebugIcon(source: CGImage) -> CGImage {
    let width = source.width
    let height = source.height
    let canvas = CGFloat(width)
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let context = CGContext(
        data: nil,
        width: width,
        height: height,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: colorSpace,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue,
    )!
    context.clear(CGRect(x: 0, y: 0, width: canvas, height: CGFloat(height)))
    context.interpolationQuality = .high
    context.draw(source, in: CGRect(x: 0, y: 0, width: canvas, height: CGFloat(height)))
    drawDebugBanner(context: context, canvas: canvas)
    return context.makeImage()!
}

let repoRoot = URL(fileURLWithPath: CommandLine.arguments[0])
    .deletingLastPathComponent()
    .deletingLastPathComponent()
let mainRes = repoRoot.appendingPathComponent("androidApp/src/main/res").path
let debugRes = repoRoot.appendingPathComponent("androidApp/src/debug/res").path

for density in densities {
    let sourceDir = (mainRes as NSString).appendingPathComponent("mipmap-\(density)")
    let destinationDir = (debugRes as NSString).appendingPathComponent("mipmap-\(density)")
    try FileManager.default.createDirectory(
        atPath: destinationDir,
        withIntermediateDirectories: true,
    )
    for iconName in iconNames {
        let sourcePath = (sourceDir as NSString).appendingPathComponent(iconName)
        guard FileManager.default.fileExists(atPath: sourcePath) else { continue }
        let image = loadImage(sourcePath)
        writePNG(
            renderDebugIcon(source: image),
            to: (destinationDir as NSString).appendingPathComponent(iconName),
        )
        print("wrote debug mipmap-\(density)/\(iconName)")
    }
}

print("done — Android debug launcher icons generated")

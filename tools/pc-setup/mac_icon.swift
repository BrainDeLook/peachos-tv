// macOS-only build asset: redraw the existing PeachOS mark at native resolution.
import AppKit
let size = 1024
let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
NSColor.clear.setFill()
NSRect(x: 0, y: 0, width: CGFloat(size), height: CGFloat(size)).fill()
// Keep transparent margins so the visible tile matches neighbouring Dock icons.
let tile = NSBezierPath(roundedRect: NSRect(x: 100, y: 100, width: 824, height: 824), xRadius: 184, yRadius: 184)
NSGradient(starting: NSColor(srgbRed: 1, green: 0.71, blue: 0.58, alpha: 1),
           ending: NSColor(srgbRed: 0.91, green: 0.63, blue: 0.69, alpha: 1))!.draw(in: tile, angle: -45)
NSColor(srgbRed: 0.13, green: 0.14, blue: 0.18, alpha: 1).setFill()
NSBezierPath(roundedRect: NSRect(x: 238, y: 345, width: 548, height: 360), xRadius: 66, yRadius: 66).fill()
for (x, color) in [(286.0, NSColor(srgbRed: 0.99, green: 0.74, blue: 0.63, alpha: 1)),
                   (438.0, NSColor(srgbRed: 0.98, green: 0.84, blue: 0.74, alpha: 1)),
                   (590.0, NSColor(srgbRed: 0.80, green: 0.73, blue: 0.85, alpha: 1))] {
    color.setFill()
    NSBezierPath(roundedRect: NSRect(x: x, y: 477, width: 112, height: 177), xRadius: 22, yRadius: 22).fill()
}
NSColor(srgbRed: 0.99, green: 0.88, blue: 0.82, alpha: 1).setFill()
NSBezierPath(roundedRect: NSRect(x: 286, y: 397, width: 264, height: 32), xRadius: 16, yRadius: 16).fill()
NSGraphicsContext.restoreGraphicsState()
try bitmap.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))

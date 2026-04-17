import SwiftUI

// Ports the Material3 Compose theme colors (Purple80/40, PurpleGrey80/40, Pink80/40)
// to SwiftUI. Only the primary colors used in the Android theme are mirrored.
extension Color {
    static let appPurple80 = Color(red: 0xD0 / 255.0, green: 0xBC / 255.0, blue: 0xFF / 255.0)
    static let appPurpleGrey80 = Color(red: 0xCC / 255.0, green: 0xC2 / 255.0, blue: 0xDC / 255.0)
    static let appPink80 = Color(red: 0xEF / 255.0, green: 0xB8 / 255.0, blue: 0xC8 / 255.0)

    static let appPurple40 = Color(red: 0x66 / 255.0, green: 0x50 / 255.0, blue: 0xA4 / 255.0)
    static let appPurpleGrey40 = Color(red: 0x62 / 255.0, green: 0x5B / 255.0, blue: 0x71 / 255.0)
    static let appPink40 = Color(red: 0x7D / 255.0, green: 0x52 / 255.0, blue: 0x60 / 255.0)
}

struct AppTheme {
    static var primary: Color { .appPurple40 }
    static var secondary: Color { .appPurpleGrey40 }
    static var tertiary: Color { .appPink40 }

    static var outline: Color { Color.gray.opacity(0.5) }
    static var onSurfaceVariant: Color { Color.secondary }
    static var surface: Color { Color(.systemBackground) }
    static var surfaceVariant: Color { Color(.secondarySystemBackground) }
    static var error: Color { Color.red }
}

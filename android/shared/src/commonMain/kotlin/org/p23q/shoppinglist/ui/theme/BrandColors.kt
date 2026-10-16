package org.p23q.shoppinglist.ui.theme

import androidx.compose.ui.graphics.Color

// The app's colour scheme, in one place: change a value here and Theme.kt builds the Material
// light and dark schemes from it. The same five roles are the web's tokens in web/src/index.css,
// and web/src/lib/brandColors.test.ts reads this file and fails if the two clients drift apart,
// so edit both together. Keep one `val` per role with a literal Color(0xFFRRGGBB) on its line:
// the test reads them by pattern.
//
// The roles:
// - Background: the screen, the top bar and every surface that is not a card. Flat.
// - Foreground: text and icons on the background.
// - Accent: buttons, checkmarks, switches and the selected state (the drawer's current entry).
// - AccentText (highlighted text): links, the "due today" date, the category and date headings
//   in a list, and the open-item count on the overview.
//   Darker than the accent in the light scheme, where the accent itself is too light to read, and
//   dark enough to clear 4.5:1 on the card surface as well as on the background.
// - OnAccent: text and icons drawn on the accent. Dark in both schemes: white on the light accent
//   read at 2.9:1.
//
// The meaning colours (error red, the balance green, the checked item's red) are not brand colours
// and live in Theme.kt; the logo keeps its own gradient in the drawable.

val BackgroundLight = Color(0xFFFFFFFF)
val BackgroundDark = Color(0xFF000000)

val ForegroundLight = Color(0xFF1A1A1E)
val ForegroundDark = Color(0xFFF2F2F4)

val AccentLight = Color(0xFF5A97FF)
val AccentDark = Color(0xFF5A97FF)

val AccentTextLight = Color(0xFF0F62F0)
val AccentTextDark = Color(0xFF5A97FF)

val OnAccentLight = Color(0xFF0B1220)
val OnAccentDark = Color(0xFF0B1220)

// Neutral greys, without the purple tint of Material's baseline: muted text, borders and the
// secondary surface (cards, dialogs, the drawer). The web's --color-text-muted, --color-border and
// --color-surface.
val MutedLight = Color(0xFF6B6B73)
val MutedDark = Color(0xFF9A9AA2)

val BorderLight = Color(0xFFE2E2E5)
val BorderDark = Color(0xFF2F2F36)

val SurfaceLight = Color(0xFFF7F7F8)
val SurfaceDark = Color(0xFF1E1E23)

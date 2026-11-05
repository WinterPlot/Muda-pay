package io.mudapay.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * MudaPay custom icon pack. 24x24 grid, 1.8 round stroke, one visual language
 * (rounded line + a small accent dot on "money" icons). Tinted by Icon(), so they follow theme colors.
 * Drop-in replacement for Icons.Default.* — same ImageVector type.
 */
object MudaIcons {
    private fun icon(name: String, vararg d: String, dots: List<Triple<Float, Float, Float>> = emptyList(), w: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            d.forEach { p ->
                addPath(
                    pathData = addPathNodes(p), fill = null,
                    stroke = SolidColor(Color.Black), strokeLineWidth = w,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
                )
            }
            dots.forEach { (cx, cy, r) ->
                addPath(
                    pathData = addPathNodes("M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"),
                    fill = SolidColor(Color.Black)
                )
            }
        }.build()

    val Info by lazy { icon("Info", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18", "M12 11v5", dots = listOf(Triple(12f, 7.6f, 1.1f))) }
    val Share by lazy { icon("Share", "M8.6 10.8l6.8-3.6", "M8.6 13.2l6.8 3.6", "M6 9.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 0 0 0-5", "M18 3.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 0 0 0-5", "M18 15.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 0 0 0-5") }
    val IosShare by lazy { icon("IosShare", "M12 15V4", "M8.5 7.5L12 4l3.5 3.5", "M7 11H6a1.5 1.5 0 0 0-1.5 1.5v6A1.5 1.5 0 0 0 6 20h12a1.5 1.5 0 0 0 1.5-1.5v-6A1.5 1.5 0 0 0 18 11h-1") }
    val Groups by lazy { icon("Groups", "M12 6.5a3 3 0 1 0 0 6a3 3 0 0 0 0-6", "M6.5 19c0-3 2.4-5 5.5-5s5.5 2 5.5 5", "M5 9.5a2.2 2.2 0 1 0 0 4.4", "M19 9.5a2.2 2.2 0 1 1 0 4.4", "M2.8 17.5c.3-1.8 1.4-3 3-3.4", "M21.2 17.5c-.3-1.8-1.4-3-3-3.4") }
    val Lock by lazy { icon("Lock", "M6.5 11h11a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 18.5v-6A1.5 1.5 0 0 1 6.5 11", "M8 11V8a4 4 0 0 1 8 0v3", dots = listOf(Triple(12f, 15.5f, 1.1f))) }
    val Storefront by lazy { icon("Storefront", "M4 9l1.2-4.5h13.6L20 9", "M4 9c0 1.4 1.1 2.5 2.5 2.5S9 10.4 9 9c0 1.4 1.1 2.5 3 2.5s3-1.1 3-2.5c0 1.4 1.1 2.5 2.5 2.5S20 10.4 20 9", "M5.5 11.5V20h13v-8.5", "M10 20v-4.5h4V20") }
    val NorthEast by lazy { icon("NorthEast", "M7 17L17 7", "M9 7h8v8") }
    val SouthWest by lazy { icon("SouthWest", "M17 7L7 17", "M15 17H7V9") }
    val ContentCopy by lazy { icon("ContentCopy", "M9.5 8h8A1.5 1.5 0 0 1 19 9.5v9a1.5 1.5 0 0 1-1.5 1.5h-8A1.5 1.5 0 0 1 8 18.5v-9A1.5 1.5 0 0 1 9.5 8", "M16 8V5.5A1.5 1.5 0 0 0 14.5 4h-8A1.5 1.5 0 0 0 5 5.5v9A1.5 1.5 0 0 0 6.5 16H8") }
    val CheckCircle by lazy { icon("CheckCircle", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18", "M8 12.3l2.6 2.6L16 9.4") }
    val TaskAlt by lazy { icon("TaskAlt", "M20.5 11.2A8.6 8.6 0 1 1 15.6 4", "M8.2 11.8l3.2 3.2L20 6.4") }
    val Check by lazy { icon("Check", "M5 12.5l4.5 4.5L19 7.5", w = 2.2f) }
    val Close by lazy { icon("Close", "M6 6l12 12", "M18 6L6 18", w = 2.2f) }
    val Cancel by lazy { icon("Cancel", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18", "M9 9l6 6", "M15 9l-6 6") }
    val Add by lazy { icon("Add", "M12 5v14", "M5 12h14", w = 2.2f) }
    val AddCircle by lazy { icon("AddCircle", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18", "M12 8v8", "M8 12h8") }
    val Notifications by lazy { icon("Notifications", "M6 17.5V11a6 6 0 0 1 12 0v6.5l1.5 1.5h-15z", "M10 21a2.2 2.2 0 0 0 4 0") }
    val NotificationsNone by lazy { Notifications }
    val NotificationsActive by lazy { icon("NotificationsActive", "M6.5 17.5V11a5.5 5.5 0 0 1 11 0v6.5l1.5 1.5H5z", "M10 21a2.2 2.2 0 0 0 4 0", "M3.5 8.5A9 9 0 0 1 6 4", "M20.5 8.5A9 9 0 0 0 18 4") }
    val NotificationsOff by lazy { icon("NotificationsOff", "M6.5 17.5V11a5.5 5.5 0 0 1 .8-2.9", "M17.5 11v6.5l1.5 1.5H8", "M10 21a2.2 2.2 0 0 0 4 0", "M4 4l16 16") }
    val Email by lazy { icon("Email", "M5.5 5.5h13A1.5 1.5 0 0 1 20 7v10a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17V7a1.5 1.5 0 0 1 1.5-1.5", "M4.5 7.5L12 13l7.5-5.5") }
    val AccountBalanceWallet by lazy { icon("Wallet", "M4 7.5A2.5 2.5 0 0 1 6.5 5H17a1.5 1.5 0 0 1 1.5 1.5V8", "M4 7.5v9A2.5 2.5 0 0 0 6.5 19h11a1.5 1.5 0 0 0 1.5-1.5v-8A1.5 1.5 0 0 0 17.5 8H6.5A2.5 2.5 0 0 1 4 5.5", "M15 13.5h5v-3h-5a1.5 1.5 0 0 0 0 3", dots = listOf(Triple(16.2f, 12f, 0.9f))) }
    val Phone by lazy { icon("Phone", "M6.6 4h2.6l1.4 3.6-1.8 1.2a11 11 0 0 0 5.4 5.4l1.2-1.8L19 14.8v2.6a1.8 1.8 0 0 1-2 1.8A14.6 14.6 0 0 1 4.8 6 1.8 1.8 0 0 1 6.6 4") }
    val PhoneAndroid by lazy { icon("PhoneAndroid", "M8 3h8a1.5 1.5 0 0 1 1.5 1.5v15A1.5 1.5 0 0 1 16 21H8a1.5 1.5 0 0 1-1.5-1.5v-15A1.5 1.5 0 0 1 8 3", "M10.5 6h3", dots = listOf(Triple(12f, 18f, 0.9f))) }
    val Smartphone by lazy { PhoneAndroid }
    val HourglassTop by lazy { icon("Hourglass", "M7 3.5h10", "M7 20.5h10", "M8 3.5c0 4 2 5 4 8.5c2-3.5 4-4.5 4-8.5", "M8 20.5c0-4 2-5 4-8.5c2 3.5 4 4.5 4 8.5") }
    val EmojiEvents by lazy { icon("Trophy", "M8 4h8v5a4 4 0 0 1-8 0z", "M8 6H5.5v1.5A3 3 0 0 0 8.5 10.5", "M16 6h2.5v1.5a3 3 0 0 1-3 3", "M12 13v4", "M8.5 20h7", "M9.5 17h5l.5 3h-6z") }
    val SupportAgent by lazy { icon("SupportAgent", "M5 14v-2a7 7 0 0 1 14 0v2", "M5 12.5h1.5a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1H5.5A1.5 1.5 0 0 1 4 16.5V14", "M19 12.5h-1.5a1 1 0 0 0-1 1v3a1 1 0 0 0 1 1h1A1.5 1.5 0 0 0 20 16", "M18 18.5c0 1.4-1.6 2.5-4 2.5") }
    val Refresh by lazy { icon("Refresh", "M19 12a7 7 0 1 1-2.1-5", "M19 4.5V8h-3.5") }
    val Sync by lazy { icon("Sync", "M5 11a7 7 0 0 1 12-3.5L19 9.5", "M19 5v4.5h-4.5", "M19 13a7 7 0 0 1-12 3.5L5 14.5", "M5 19v-4.5h4.5") }
    val QrCodeScanner by lazy { icon("QrScanner", "M4 8V5.5A1.5 1.5 0 0 1 5.5 4H8", "M16 4h2.5A1.5 1.5 0 0 1 20 5.5V8", "M20 16v2.5a1.5 1.5 0 0 1-1.5 1.5H16", "M8 20H5.5A1.5 1.5 0 0 1 4 18.5V16", "M7.5 8.5h3v3h-3z", "M13.5 8.5h3v3h-3z", "M7.5 14.5h3v1h-3z", "M13.5 14h3v2.5h-3z") }
    val QrCode2 by lazy { QrCode }
    val QrCode by lazy { icon("QrCode", "M4.5 4.5h6v6h-6z", "M13.5 4.5h6v6h-6z", "M4.5 13.5h6v6h-6z", "M13.5 13.5h2.5v2.5h-2.5z", "M18 13.5h1.5v1.5", "M13.5 18h1.5", "M17 18h2.5v1.5") }
    val AutoAwesome by lazy { icon("Sparkles", "M10 4l1.6 4.4L16 10l-4.4 1.6L10 16l-1.6-4.4L4 10l4.4-1.6z", "M18 14l.8 2.2L21 17l-2.2.8L18 20l-.8-2.2L15 17l2.2-.8z") }
    val ReceiptLong by lazy { icon("Receipt", "M6 3.5h12v17l-2.2-1.5-1.9 1.5-1.9-1.5-1.9 1.5-1.9-1.5L6 20.5z", "M9 8h6", "M9 11.5h6", "M9 15h3.5") }
    val Receipt by lazy { ReceiptLong }
    val Settings by lazy { icon("Settings", "M12 9a3 3 0 1 0 0 6a3 3 0 0 0 0-6", "M19.2 14a1.3 1.3 0 0 0 .3 1.4l.1.1a1.6 1.6 0 1 1-2.3 2.3l-.1-.1a1.3 1.3 0 0 0-1.4-.3 1.3 1.3 0 0 0-.8 1.2V19a1.6 1.6 0 1 1-3.2 0v-.1a1.3 1.3 0 0 0-.9-1.2 1.3 1.3 0 0 0-1.4.3l-.1.1a1.6 1.6 0 1 1-2.3-2.3l.1-.1a1.3 1.3 0 0 0 .3-1.4 1.3 1.3 0 0 0-1.2-.8H5a1.6 1.6 0 1 1 0-3.2h.1a1.3 1.3 0 0 0 1.2-.9 1.3 1.3 0 0 0-.3-1.4l-.1-.1A1.6 1.6 0 1 1 8 5.7l.1.1a1.3 1.3 0 0 0 1.4.3h.1a1.3 1.3 0 0 0 .8-1.2V5a1.6 1.6 0 1 1 3.2 0v.1a1.3 1.3 0 0 0 .8 1.2 1.3 1.3 0 0 0 1.4-.3l.1-.1a1.6 1.6 0 1 1 2.3 2.3l-.1.1a1.3 1.3 0 0 0-.3 1.4v.1a1.3 1.3 0 0 0 1.2.8h.1a1.6 1.6 0 1 1 0 3.2h-.1a1.3 1.3 0 0 0-1.2.8") }
    val Search by lazy { icon("Search", "M11 4a7 7 0 1 0 0 14a7 7 0 0 0 0-14", "M20 20l-4.2-4.2") }
    val SearchOff by lazy { icon("SearchOff", "M11 4a7 7 0 1 0 0 14a7 7 0 0 0 0-14", "M20 20l-4.2-4.2", "M8.5 8.5l5 5", "M13.5 8.5l-5 5") }
    val PlayCircle by lazy { icon("PlayCircle", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18", "M10 8.5v7l5.5-3.5z") }
    val PlayArrow by lazy { icon("PlayArrow", "M8 5.5v13l10.5-6.5z") }
    val Stop by lazy { icon("Stop", "M7 7h10v10H7z") }
    val Person by lazy { icon("Person", "M12 4a4 4 0 1 0 0 8a4 4 0 0 0 0-8", "M4.5 20c.5-3.6 3.5-6 7.5-6s7 2.4 7.5 6") }
    val AccountCircle by lazy { icon("AccountCircle", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18", "M12 7.5a3 3 0 1 0 0 6a3 3 0 0 0 0-6", "M6.5 18c1-2.2 3-3.5 5.5-3.5s4.5 1.3 5.5 3.5") }
    val PersonSearch by lazy { icon("PersonSearch", "M10 4a3.5 3.5 0 1 0 0 7a3.5 3.5 0 0 0 0-7", "M3.5 19.5c.5-3 3-5 6.5-5", "M16.5 13a3 3 0 1 0 0 6a3 3 0 0 0 0-6", "M21 21l-2.4-2.4") }
    val CandlestickChart by lazy { icon("CandlestickChart", "M7 3.500v3", "M7 17.500v3", "M5 6.500h4v11H5z", "M17 2.500v3", "M17 13.500v5", "M15 5.500h4v8h-4z") }
    val Percent by lazy { icon("Percent", "M18.5 5.5l-13 13", "M7.5 5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 0 0 0-5", "M16.5 14a2.5 2.5 0 1 0 0 5a2.5 2.5 0 0 0 0-5") }
    val MyLocation by lazy { icon("MyLocation", "M12 7.5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 0 0 0-9", "M12 2.5v3", "M12 18.5v3", "M2.5 12h3", "M18.5 12h3", dots = listOf(Triple(12f, 12f, 1.2f))) }
    val LocationOn by lazy { icon("LocationOn", "M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11", dots = listOf(Triple(12f, 10f, 2.2f))) }
    val ExpandMore by lazy { icon("ExpandMore", "M6 9.5l6 6 6-6", w = 2.2f) }
    val ExpandLess by lazy { icon("ExpandLess", "M6 14.5l6-6 6 6", w = 2.2f) }
    val ChevronRight by lazy { icon("ChevronRight", "M9.5 6l6 6-6 6", w = 2.2f) }
    val CurrencyBitcoin by lazy { icon("Bitcoin", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18", "M10 7.5v9", "M10 7.5h3a2 2 0 0 1 0 4h-3", "M10 11.5h3.5a2.5 2.5 0 0 1 0 5H10", "M11.5 6v1.5", "M11.5 16.5V18") }
    val CloudOff by lazy { icon("CloudOff", "M7 18.5h9.5a3.5 3.5 0 0 0 1.4-6.7A5.5 5.5 0 0 0 8.2 8", "M5.5 9.5A5 5 0 0 0 7 18.5", "M4 4l16 16") }
    val WifiOff by lazy { icon("WifiOff", "M3 9.5a14 14 0 0 1 4-2.4", "M21 9.5a14 14 0 0 0-8.5-3.4", "M6.5 13a8.5 8.5 0 0 1 3-1.8", "M17.5 13a8.5 8.5 0 0 0-3-1.8", "M10 16.5a4 4 0 0 1 4 0", "M4 4l16 16", dots = listOf(Triple(12f, 19.5f, 1.1f))) }
    val Android by lazy { icon("Android", "M6 10h12v7a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 6 17z", "M6 10a6 6 0 0 1 12 0", "M8.5 4.5L7.3 3", "M15.5 4.5L16.7 3", "M3.5 11v5", "M20.5 11v5", dots = listOf(Triple(9.5f, 8f, 0.8f), Triple(14.5f, 8f, 0.8f))) }
    val SystemUpdate by lazy { icon("SystemUpdate", "M8 3h8a1.5 1.5 0 0 1 1.5 1.5v15A1.5 1.5 0 0 1 16 21H8a1.5 1.5 0 0 1-1.5-1.5v-15A1.5 1.5 0 0 1 8 3", "M12 7.5v6", "M9.5 11.2l2.5 2.5 2.5-2.5") }
    val RequestQuote by lazy { icon("RequestQuote", "M6 3.5h8l4 4v13H6z", "M14 3.5v4h4", "M9 12.5h6", "M9 16h4") }
    val Public by lazy { icon("Public", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18", "M3 12h18", "M12 3c2.6 2.6 3.8 5.6 3.8 9S14.6 18.4 12 21c-2.6-2.6-3.8-5.6-3.8-9S9.4 5.6 12 3") }
    val Menu by lazy { icon("Menu", "M4 7h16", "M4 12h16", "M4 17h10", w = 2.2f) }
    val Inbox by lazy { icon("Inbox", "M4 13l2.2-7.2A1.5 1.5 0 0 1 7.6 4.8h8.8a1.5 1.5 0 0 1 1.4 1L20 13", "M4 13v5.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V13h-4.5l-1 2.5h-5l-1-2.5z") }
    val Image by lazy { icon("Image", "M5.5 4.5h13A1.5 1.5 0 0 1 20 6v12a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18V6a1.5 1.5 0 0 1 1.5-1.5", "M4.5 16l4.5-4.5 3.5 3.5 2.5-2.5 4.5 4.5", dots = listOf(Triple(9f, 8.5f, 1.3f))) }
    val PhotoLibrary by lazy { icon("PhotoLibrary", "M8 3.5h11A1.5 1.5 0 0 1 20.5 5v11A1.5 1.5 0 0 1 19 17.5H8A1.5 1.5 0 0 1 6.5 16V5A1.5 1.5 0 0 1 8 3.5", "M6.5 7H5a1.5 1.5 0 0 0-1.5 1.5v11A1.5 1.5 0 0 0 5 21h11a1.5 1.5 0 0 0 1.5-1.5V19", "M7 14.5l3.5-3.5 3 3 2-2 3 3") }
    val ErrorOutline by lazy { icon("Error", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18", "M12 7.5v5.5", dots = listOf(Triple(12f, 16.4f, 1.1f))) }
    val Warning by lazy { icon("Warning", "M12 4L2.8 19.5h18.4z", "M12 10v4.5", dots = listOf(Triple(12f, 17.2f, 1f))) }
    val Edit by lazy { icon("Edit", "M4 20l1-4.5L16.5 4a2 2 0 0 1 2.8 0l.7.7a2 2 0 0 1 0 2.8L8.5 19z", "M14.5 6l3.5 3.5") }
    val Contacts by lazy { icon("Contacts", "M6 3.5h12A1.5 1.5 0 0 1 19.5 5v14a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 19V5A1.5 1.5 0 0 1 6 3.5", "M12 7.8a2.3 2.3 0 1 0 0 4.6a2.3 2.3 0 0 0 0-4.6", "M8 17c.4-1.8 2-2.8 4-2.8s3.6 1 4 2.8") }
    val AddCard by lazy { icon("AddCard", "M13.5 18.5h-8A1.5 1.5 0 0 1 4 17V7a1.5 1.5 0 0 1 1.5-1.5h13A1.5 1.5 0 0 1 20 7v5", "M4 10h16", "M18 15v6", "M15 18h6") }
    val CreditCard by lazy { icon("CreditCard", "M5.5 5.5h13A1.5 1.5 0 0 1 20 7v10a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17V7a1.5 1.5 0 0 1 1.5-1.5", "M4 10h16", "M7.5 15h3") }
    val Payments by lazy { icon("Payments", "M3.5 7h17v10h-17z", "M6.5 7a3 3 0 0 1-3 3", "M17.5 17a3 3 0 0 1 3-3", "M12 9.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 0 0 0-5") }
    val UploadFile by lazy { icon("UploadFile", "M6 3.5h8l4 4v13H6z", "M14 3.5v4h4", "M12 17v-5.5", "M9.7 13.7L12 11.4l2.3 2.3") }
    val ThumbUp by lazy { icon("ThumbUp", "M7.5 10.5V20H5a1 1 0 0 1-1-1v-7.5a1 1 0 0 1 1-1z", "M7.5 10.5l3-6.2a1.7 1.7 0 0 1 3.2.9L13 9h5.3a1.7 1.7 0 0 1 1.7 2l-1.2 6.5a2.5 2.5 0 0 1-2.5 2H7.5") }
    val ThumbDown by lazy { icon("ThumbDown", "M7.5 13.5V4H5a1 1 0 0 0-1 1v7.5a1 1 0 0 0 1 1z", "M7.5 13.5l3 6.2a1.7 1.7 0 0 0 3.2-.9L13 15h5.3a1.7 1.7 0 0 0 1.7-2L18.8 6.5A2.5 2.5 0 0 0 16.3 4.5H7.5") }
    val Sms by lazy { icon("Sms", "M5.5 4.5h13A1.5 1.5 0 0 1 20 6v9a1.5 1.5 0 0 1-1.5 1.5H9.5L5 20v-3.5H5.5A1.5 1.5 0 0 1 4 15V6a1.5 1.5 0 0 1 1.5-1.5", dots = listOf(Triple(8.5f, 10.5f, 1f), Triple(12f, 10.5f, 1f), Triple(15.5f, 10.5f, 1f))) }
    val Chat by lazy { icon("Chat", "M12 4a8 7.5 0 1 0 0 15c1 0 1.9-.2 2.8-.5L19.5 20l-1.2-3.6A7.200 7.200 0 0 0 20 11.5C20 7.4 16.4 4 12 4") }
    val AddComment by lazy { icon("AddComment", "M20 12v3a1.5 1.5 0 0 1-1.5 1.5H9.5L5 20v-3.5H5.5A1.5 1.5 0 0 1 4 15V6a1.5 1.5 0 0 1 1.5-1.5H13", "M18 3v6", "M15 6h6") }
    val Redeem by lazy { icon("Redeem", "M4 9h16v4H4z", "M5.5 13v6.5h13V13", "M12 9v10.5", "M12 9c-1.5-.2-4-.8-4-3a2 2 0 0 1 4-.3", "M12 9c1.5-.2 4-.8 4-3a2 2 0 0 0-4-.3") }
    val CardGiftcard by lazy { Redeem }
    val MoreHoriz by lazy { icon("MoreHoriz", dots = listOf(Triple(5.5f, 12f, 1.6f), Triple(12f, 12f, 1.6f), Triple(18.5f, 12f, 1.6f))) }
    val Mic by lazy { icon("Mic", "M12 3.5a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0v-5a3 3 0 0 0-3-3", "M6 11.5a6 6 0 0 0 12 0", "M12 17.5v3", "M9 20.5h6") }
    val Link by lazy { icon("Link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3A4 4 0 0 0 10 18.700l1-1") }
    val Keyboard by lazy { icon("Keyboard", "M4.5 6.5h15A1.5 1.5 0 0 1 21 8v8a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 16V8a1.5 1.5 0 0 1 1.5-1.5", "M7 10.5h.5", "M10 10.5h.5", "M13.500 10.5h.5", "M16.500 10.5h.5", "M8 14h8") }
    val Home by lazy { icon("Home", "M4 11l8-7 8 7", "M6 9.500V19a1.500 1.500 0 0 0 1.500 1.500h9A1.500 1.500 0 0 0 18 19V9.500", "M10 20.500V15h4v5.500") }
    val History by lazy { icon("History", "M4 12a8 8 0 1 0 2.5-5.800", "M4 4.500V8.500h4", "M12 8v4.500l3 1.800") }
    val Schedule by lazy { icon("Schedule", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18", "M12 7.500V12l3 2") }
    val AccessTime by lazy { Schedule }
    val Directions by lazy { icon("Directions", "M12 2.800l9.200 9.200L12 21.200 2.800 12z", "M9 15v-3a1.500 1.500 0 0 1 1.500-1.500H15", "M13 8.500l2.500 2-2.500 2") }
    val CameraAlt by lazy { icon("Camera", "M4.500 7.500h3l1.500-2.500h6l1.500 2.500h3A1.500 1.500 0 0 1 21 9v9a1.500 1.500 0 0 1-1.500 1.500h-15A1.500 1.500 0 0 1 3 18V9a1.500 1.500 0 0 1 1.500-1.500", "M12 10a3.500 3.500 0 1 0 0 7a3.500 3.500 0 0 0 0-7") }
    val CalendarMonth by lazy { icon("Calendar", "M5.500 5.500h13A1.500 1.500 0 0 1 20 7v11.500a1.500 1.500 0 0 1-1.500 1.500h-13A1.500 1.500 0 0 1 4 18.500V7a1.500 1.500 0 0 1 1.500-1.500", "M4 10h16", "M8 3.500v4", "M16 3.500v4") }
    val Badge by lazy { icon("Badge", "M6 4.500h12A1.500 1.500 0 0 1 19.500 6v13.500A1.500 1.500 0 0 1 18 21H6a1.500 1.500 0 0 1-1.500-1.500V6A1.500 1.500 0 0 1 6 4.500", "M10 3h4v3h-4z", "M12 10a2 2 0 1 0 0 4a2 2 0 0 0 0-4", "M8.500 18c.4-1.500 1.700-2.300 3.500-2.300s3.100.8 3.500 2.300") }
    val Visibility by lazy { icon("Visibility", "M2.500 12S6 5.500 12 5.500 21.500 12 21.500 12 18 18.500 12 18.500 2.500 12 2.500 12", "M12 9.500a2.500 2.500 0 1 0 0 5a2.500 2.500 0 0 0 0-5") }
    val VisibilityOff by lazy { icon("VisibilityOff", "M9.800 5.800A9 9 0 0 1 12 5.500C18 5.500 21.500 12 21.500 12a15 15 0 0 1-2.600 3.400", "M6.200 7.500A15 15 0 0 0 2.500 12S6 18.500 12 18.500a8.800 8.800 0 0 0 4-1", "M10 10a2.500 2.500 0 0 0 3.500 3.500", "M4 4l16 16") }
    val Send by lazy { icon("Send", "M20.500 3.500L10.500 13.500", "M20.500 3.500L14 20.500l-3.500-7-7-3.500z") }
    val ArrowBack by lazy { icon("ArrowBack", "M19 12H5", "M11 6l-6 6 6 6", w = 2.2f) }
    val ArrowForward by lazy { icon("ArrowForward", "M5 12h14", "M13 6l6 6-6 6", w = 2.2f) }
    val Logout by lazy { icon("Logout", "M10 4.500H6.500A1.500 1.500 0 0 0 5 6v12a1.500 1.500 0 0 0 1.500 1.500H10", "M15.500 8l4 4-4 4", "M19.500 12H9.500") }
    val Security by lazy { icon("Security", "M12 3l7.500 3v5.500c0 4.500-3.100 8-7.500 9.500-4.400-1.500-7.500-5-7.500-9.500V6z", "M8.800 12l2.300 2.300 4.200-4.500") }
    val PrivacyTip by lazy { icon("PrivacyTip", "M12 3l7.500 3v5.500c0 4.500-3.100 8-7.500 9.500-4.400-1.500-7.500-5-7.500-9.500V6z", "M12 11v4", dots = listOf(Triple(12f, 8.300f, 1f))) }
    val Fingerprint by lazy { icon("Fingerprint", "M6 9a6 6 0 0 1 12 0", "M5 13.500a7 7 0 0 1 .3-2", "M8.500 9.500a3.500 3.500 0 0 1 7 0v2c0 3 1 5 2 6.500", "M12 9.500v3c0 3 .8 5.500 2 7.500", "M8.500 12.500c0 3 .5 5.500 1.500 7.500", "M19 14c-.2-1-.4-2-.4-2.800") }
    val FlashOn by lazy { icon("FlashOn", "M13 3L5.500 13.500H11L10 21l7.500-10.500H12z") }
    val Bolt by lazy { FlashOn }
    val FlashOff by lazy { icon("FlashOff", "M14.500 4.500L13 3 8 10", "M11 13.500H5.500L8 10", "M10 21l1-7.500", "M17.500 10.500H13", "M4 4l16 16") }
    val FilterList by lazy { icon("FilterList", "M4 7h16", "M7 12h10", "M10 17h4", w = 2.200f) }
    val Download by lazy { icon("Download", "M12 4v11", "M7.500 10.500L12 15l4.500-4.500", "M5 19.500h14") }
    val Dialpad by lazy { icon("Dialpad", dots = listOf(Triple(6f, 5f, 1.500f), Triple(12f, 5f, 1.500f), Triple(18f, 5f, 1.500f), Triple(6f, 11f, 1.500f), Triple(12f, 11f, 1.500f), Triple(18f, 11f, 1.500f), Triple(6f, 17f, 1.500f), Triple(12f, 17f, 1.500f), Triple(18f, 17f, 1.500f), Triple(12f, 21.500f, 1.200f))) }
    val Description by lazy { icon("Description", "M6 3.500h8l4 4v13H6z", "M14 3.500v4h4", "M9 12.500h6", "M9 16h6") }
    val PictureAsPdf by lazy { icon("Pdf", "M6 3.500h8l4 4v13H6z", "M14 3.500v4h4", "M8.500 16.500c1.500-.5 3-2.500 3.500-5.500.500 3 2 5 3.500 5.500-2-.8-5-.8-7 0") }
    val DeleteOutline by lazy { icon("Delete", "M4.500 7h15", "M9.500 7V4.500h5V7", "M6.500 7l.8 12.500a1.500 1.500 0 0 0 1.500 1.500h6.400a1.500 1.500 0 0 0 1.500-1.500L17.500 7", "M10 11v6", "M14 11v6") }
    val DeleteForever by lazy { icon("DeleteForever", "M4.500 7h15", "M9.500 7V4.500h5V7", "M6.500 7l.8 12.500a1.500 1.500 0 0 0 1.500 1.500h6.400a1.500 1.500 0 0 0 1.500-1.500L17.500 7", "M10 11l4 5", "M14 11l-4 5") }
    val Campaign by lazy { icon("Campaign", "M4 10v4a1 1 0 0 0 1 1h2.500L14 19V5L7.500 9H5a1 1 0 0 0-1 1", "M17 9a4 4 0 0 1 0 6", "M19.500 6.500a8 8 0 0 1 0 11") }
    val Business by lazy { icon("Business", "M5 20.500V5a1.500 1.500 0 0 1 1.500-1.500h7A1.500 1.500 0 0 1 15 5v15.500", "M15 10h3.500A1.500 1.500 0 0 1 20 11.500v9", "M3.500 20.500h17", "M8.500 7.500h3", "M8.500 11h3", "M8.500 14.500h3") }
    val Apps by lazy { icon("Apps", "M4.500 4.500h5.500v5.500H4.500z", "M14 4.500h5.500v5.500H14z", "M4.500 14h5.500v5.500H4.500z", "M14 14h5.500v5.500H14z") }
    val Leaderboard by lazy { icon("Leaderboard", "M9.500 10h5v10.500h-5z", "M3.500 14h6v6.500h-6z", "M14.500 12.500h6v8h-6z", "M12 3.500l.8 1.700 1.800.2-1.300 1.200.4 1.800-1.700-.9-1.700.9.4-1.800-1.300-1.200 1.800-.2z") }
    val Lightbulb by lazy { icon("Lightbulb", "M9 17.500h6", "M10 20.500h4", "M12 3.500a6 6 0 0 0-3.500 10.900c.6.500 1 1.200 1 2V17h5v-.6c0-.8.4-1.500 1-2A6 6 0 0 0 12 3.500") }
    val School by lazy { icon("School", "M12 4L2.500 9 12 14l9.500-5z", "M6 11.500V16c0 1.500 2.700 3 6 3s6-1.500 6-3v-4.500", "M21.500 9v6") }
    val Map by lazy { icon("Map", "M3.500 6.500l5.500-2 6 2 5.500-2v13l-5.500 2-6-2-5.500 2z", "M9 4.500v13", "M15 6.500v13") }
}

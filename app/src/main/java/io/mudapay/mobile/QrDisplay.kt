package io.mudapay.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * One look for every screen that shows a QR code (Receive, Shop, Invite, Crypto deposit, Wallet receive):
 *   QrPanel   - a white rounded card with the code, an optional title / subtitle above and an optional caption (address) below
 *   QrActions - two quiet buttons under the card: Copy and Share
 * The card stays white in light AND dark mode: a QR code must keep a light background to scan well.
 */

private val QrInk = Color(0xFF0F172A)
private val QrSub = Color(0xFF64748B)

@Composable
fun QrPanel(
    qr: ImageBitmap?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    caption: String? = null,
    monoCaption: Boolean = true
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.White).padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!title.isNullOrBlank()) Text(title, color = QrInk, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center)
        if (!subtitle.isNullOrBlank()) Text(subtitle, color = QrSub, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        Box(Modifier.fillMaxWidth().widthIn(max = 300.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
            if (qr != null) Image(qr, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else CircularProgressIndicator(Modifier.size(28.dp), color = Green, strokeWidth = 3.dp)
        }
        if (!caption.isNullOrBlank()) Text(
            caption, color = QrInk, fontWeight = FontWeight.SemiBold, fontSize = if (monoCaption) 13.sp else 14.sp, lineHeight = 19.sp,
            fontFamily = if (monoCaption) FontFamily.Monospace else FontFamily.Default, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

/** Copy + Share under a [QrPanel]. Pass null to hide one of them. */
@Composable
fun QrActions(onCopy: (() -> Unit)?, onShare: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onCopy != null) QrActionButton(L("Copy"), MudaIcons.ContentCopy, Modifier.weight(1f), enabled, onCopy)
        if (onShare != null) QrActionButton(L("Share"), MudaIcons.Share, Modifier.weight(1f), enabled, onShare)
    }
}

@Composable
private fun QrActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier.height(54.dp).clip(BtnShape).background(Soft).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = if (enabled) Ink else Muted, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (enabled) Ink else Muted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

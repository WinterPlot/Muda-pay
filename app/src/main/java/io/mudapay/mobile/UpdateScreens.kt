package io.mudapay.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun kindLabel(kind: String) = when (kind) {
    "release" -> "Release"
    "patch" -> "Patch"
    "hotfix" -> "Hotfix"
    "security" -> "Security fix"
    else -> "Update"
}

/** "3 days left" / "Last day to update: today" */
private fun daysText(i: UpdateInfo): String = when (i.daysLeft) {
    0 -> L("Last day to update: today")
    1 -> L("1 day left to update")
    else -> "${i.daysLeft} " + L("days left to update")
}

/** Button / progress / hint for the download + install, shared by the full screen and the dialog. */
@Composable
private fun UpdateActions(i: UpdateInfo, onDark: Boolean) {
    val ctx = LocalContext.current
    val sub = if (onDark) Color.White.copy(alpha = .8f) else Muted
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (AppUpdate.phase) {
            "downloading" -> {
                LinearProgressIndicator(progress = { AppUpdate.progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = Green, trackColor = if (onDark) Color.White.copy(alpha = .2f) else Soft)
                Text(L("Downloading…") + " " + (AppUpdate.progress * 100).toInt() + "%", color = sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            "ready" -> PrimaryButton(L("Install now"), { AppUpdate.install(ctx) }, icon = MudaIcons.SystemUpdate)
            else -> PrimaryButton(
                L(if (i.isFile) "Update now" else "Open download page"), { AppUpdate.start(ctx) },
                icon = if (i.isFile) MudaIcons.Download else MudaIcons.SystemUpdate
            )
        }
        AppUpdate.message?.let { Text(it, color = if (AppUpdate.phase == "error") (if (onDark) Color(0xFFFCA5A5) else Danger) else sub, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp) }
    }
}

/**
 * Replaces the whole app once the last day has passed. Nothing else can be used until the new version is installed.
 * (If the admin makes the update optional or unpublishes it, the next check removes this screen.)
 */
@Composable
fun ForceUpdateScreen(i: UpdateInfo) {
    SystemBars(darkIcons = false)
    Box(Modifier.fillMaxSize().background(Gradient)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.size(96.dp).background(Color.White.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(70.dp).background(Green, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(MudaIcons.SystemUpdate, null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(L("Update required"), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(L("To keep using MudaPay, please install the new version."), color = Color.White.copy(alpha = .85f), fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("MudaPay ${i.versionName} • ${L(kindLabel(i.kind))}", color = Color.White.copy(alpha = .7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .1f)).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(softCase(L("WHAT'S NEW")), color = Color.White.copy(alpha = .7f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(i.notes.ifBlank { L("Improvements and fixes.") }, color = Color.White, fontSize = 14.sp, lineHeight = 21.sp)
            }
            Spacer(Modifier.height(18.dp))
            UpdateActions(i, onDark = true)
            Spacer(Modifier.height(10.dp))
            Text(L("Your balance and your data are safe. Updating takes about a minute."), color = Color.White.copy(alpha = .65f), fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

/** Optional update, or a required one whose last day has not come yet: the app keeps working, this box can be closed with "Later". */
@Composable
fun UpdatePromptDialog() {
    val ctx = LocalContext.current
    val i = AppUpdate.info ?: return
    if (!AppUpdate.shouldPrompt(ctx)) return
    val required = i.mode == "required"
    AlertDialog(
        onDismissRequest = { if (AppUpdate.phase != "downloading") AppUpdate.dismissPrompt(ctx) },
        containerColor = Panel,
        title = { Text(L(if (required) "Update required" else "Update available"), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("MudaPay ${i.versionName} • ${L(kindLabel(i.kind))}", color = Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (required) {
                    Text(daysText(i), color = Danger, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(L("After the last day the app cannot be used until it is updated.") + (i.deadline?.let { " ($it)" } ?: ""), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                }
                if (i.notes.isNotBlank()) Text(i.notes, color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
                UpdateActions(i, onDark = false)
            }
        },
        confirmButton = {},
        dismissButton = {
            if (AppUpdate.phase != "downloading") TextButton(onClick = { AppUpdate.dismissPrompt(ctx) }) { Text(L("Later"), color = Muted, fontWeight = FontWeight.SemiBold) }
        }
    )
}

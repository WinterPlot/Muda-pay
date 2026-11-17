package io.mudapay.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Member safety (Google Play user-generated-content rules): payment requests carry a free-text note written by another member,
 * so the person who receives one can REPORT the sender (support reviews it: admin_reports.php) and BLOCK him.
 * A blocked member can no longer send requests or money to the blocker and is never told. Blocked members are listed in
 * Settings > Account > Blocked members, where each one can be unblocked. Server: api/v1/lib_safety.php.
 *
 * Muda AI (Google Play "AI-generated content" rule): every answer, in the chat, in the voice chat and in the assistant overlay, can be
 * reported with [ReportAiAnswerDialog]; the question and the answer are sent so support can review it (admin_reports.php > AI answers).
 */
private val reportReasons = listOf(
    "abusive_note" to "Abusive or offensive note",
    "harassment" to "Harassment or threats",
    "scam" to "Scam or fraud",
    "spam" to "Spam or unwanted requests",
    "other" to "Something else"
)

@Composable
fun BlockMemberDialog(requestId: Int, name: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Panel,
        title = { Text(L("Block this member?"), fontWeight = FontWeight.Bold) },
        text = {
            Text("$name — " + L("They will not be able to send you payment requests or money, and they are not told. Their open requests are declined. You can unblock them in Settings > Account."), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val res = Api.call("POST", "/requests/$requestId/block")
                    busy = false
                    if (res.ok) { Ui.notify("Blocked", "This member is blocked."); onDone() } else Ui.notify("Block", res.message)
                    onDismiss()
                }
            }) { Text(L("Block"), color = Danger, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(L("Cancel"), color = Muted) } }
    )
}

@Composable
fun ReportMemberDialog(requestId: Int, name: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("abusive_note") }
    var details by remember { mutableStateOf("") }
    var alsoBlock by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Panel,
        title = { Text(L("Report this member"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$name — " + L("The MudaPay team reviews every report. The member is not told who reported him."), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                Dropdown("Why are you reporting this member?", reason, reportReasons.map { it.first to L(it.second) }) { reason = it }
                InputField("Details (optional)", details, { details = it.take(400) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = alsoBlock, onCheckedChange = { alsoBlock = it }, colors = CheckboxDefaults.colors(checkedColor = GreenDark))
                    Text(L("Also block this member"), color = Ink, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val body = JSONObject().put("reason", reason).put("details", details.trim()).put("block", alsoBlock)
                    val res = Api.call("POST", "/requests/$requestId/report", body)
                    busy = false
                    if (res.ok) { Ui.notify("Report sent", "Thank you. The MudaPay team will review it."); onDone() } else Ui.notify("Report", res.message)
                    onDismiss()
                }
            }) { Text(L("Send report"), color = Danger, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(L("Cancel"), color = Muted) } }
    )
}

/** Settings > Account: the members this person blocked, each with an Unblock button. */
@Composable
fun BlockedMembersSection() {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(L("Blocked members"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(L("Blocked members cannot send you payment requests or money, and they are not told."), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Remote(call = { Api.call("GET", "/blocks") }) { d, reload ->
            val items = d.optJSONArray("items").objects()
            if (items.isEmpty()) Text(L("You have not blocked anyone."), color = Muted, fontSize = 13.sp)
            items.forEach { b ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.str("name").ifEmpty { "MudaPay member" }, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                        Text(b.str("phone"), color = Muted, fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            val res = Api.call("POST", "/blocks/${b.int("id")}/remove")
                            if (res.ok) Ui.notify("Unblocked", "This member is no longer blocked.") else Ui.notify("Blocked members", res.message)
                            reload()
                        }
                    }) { Text(L("Unblock"), color = GreenDark, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

private val aiReportReasons = listOf(
    "offensive" to "Offensive or hateful",
    "sexual" to "Sexual content",
    "harmful" to "Dangerous or harmful advice",
    "wrong_info" to "Wrong or misleading information",
    "scam" to "Looks like a scam",
    "other" to "Something else"
)

/** Report a Muda AI answer. [surface] is "chat", "voice" or "assistant"; [prompt] is the question that was asked. */
@Composable
fun ReportAiAnswerDialog(surface: String, answer: String, prompt: String, conversationId: Int, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("offensive") }
    var details by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Panel,
        title = { Text(L("Report this answer"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L("Tell us what is wrong with this Muda AI answer. The MudaPay team reviews every report and uses it to make the AI safer."), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                Dropdown("What is wrong with this answer?", reason, aiReportReasons.map { it.first to L(it.second) }) { reason = it }
                InputField("Details (optional)", details, { details = it.take(400) })
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && answer.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val body = JSONObject().put("reason", reason).put("details", details.trim()).put("answer", answer.take(4000))
                        .put("prompt", prompt.take(600)).put("surface", surface)
                    if (conversationId > 0) body.put("conversation_id", conversationId)
                    val res = Api.call("POST", "/coach/report", body)
                    busy = false
                    if (res.ok) Ui.notify("Report sent", "Thank you. The MudaPay team will review it.") else Ui.notify("Muda AI", res.message)
                    onDismiss()
                }
            }) { Text(L("Send report"), color = Danger, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(L("Cancel"), color = Muted) } }
    )
}

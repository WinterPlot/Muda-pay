package io.mudapay.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Profile pictures (v3.9).
 *
 *  - [mine]  : the signed-in member's own photo (URL) or null. Loaded by GET /account/avatar, changed by [ProfilePhotoDialog].
 *  - [known] : photos of OTHER members, by phone number ("" = that member has no photo). Filled in batches by POST /avatars/lookup,
 *              so a list of 20 people costs one request, not 20.
 *
 * Screens never deal with this directly: they draw [MemberAvatar] and it shows the photo when there is one, the initials otherwise.
 * If the server already puts an "avatar" URL next to a member (counterparty.avatar, recipient.avatar, member.avatar) pass it as `url`
 * and no lookup is made.
 */
object Avatars {
    var mine by mutableStateOf<String?>(null)
        private set

    /** phone (as the server shows it) -> photo URL, "" when that member has no photo. */
    val known: SnapshotStateMap<String, String> = mutableStateMapOf()

    /** phone -> true when that member's identity is verified (blue badge next to the photo). */
    val verified: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    /** The signed-in member's own identity is verified. */
    var mineVerified by mutableStateOf(false)
        private set

    private val asked = HashSet<String>()
    private val pending = LinkedHashSet<String>()
    private val lock = Mutex()

    @JvmName("applyMine")
    fun setMine(url: String?) { mine = url?.takeIf { it.isNotBlank() } }

    fun reset() {
        mine = null
        mineVerified = false
        known.clear()
        verified.clear()
        synchronized(pending) { asked.clear(); pending.clear() }
    }

    suspend fun loadMine() {
        val r = Api.call("GET", "/account/avatar")
        if (r.ok) { setMine(r.data.str("avatar_url")); mineVerified = r.data.optBoolean("verified", false) }
    }

    /** Ask for the photo of this phone number. Calls made close together (one list on screen) are sent as ONE request. */
    suspend fun want(phone: String) {
        val key = phone.trim()
        if (key.length < 6 || known.containsKey(key)) return
        val first = synchronized(pending) { if (asked.add(key)) { pending.add(key); true } else false }
        if (!first) return
        delay(150)
        flush()
    }

    private suspend fun flush() {
        lock.withLock {
            while (true) {
                val batch = synchronized(pending) {
                    val b = pending.take(30)
                    pending.removeAll(b.toSet())
                    b
                }
                if (batch.isEmpty()) return
                val arr = JSONArray()
                batch.forEach { arr.put(it) }
                val r = Api.call("POST", "/avatars/lookup", JSONObject().put("phones", arr))
                if (r.ok) {
                    val found = r.data.optJSONObject("avatars")
                    val ver = r.data.optJSONObject("verified")
                    batch.forEach { known[it] = found?.str(it).orEmpty(); verified[it] = ver?.optBoolean(it, false) == true }
                } else {
                    // no connection (or an old server without this route): forget, so the next time the row is drawn it asks again
                    synchronized(pending) { asked.removeAll(batch.toSet()) }
                    return
                }
            }
        }
    }
}

private fun avatarInitials(name: String): String =
    name.trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "M" }

/**
 * A round member picture. Shows the photo when the member has one, otherwise the initials on a tinted circle (the old look).
 *
 * @param phone  the member's phone number as the server shows it: used to look the photo up.
 * @param url    the photo URL when the server already sent it (then no lookup is made). Use `Avatars.mine` for the signed-in member.
 * @param filled navy gradient + white initials (home bar, profile) instead of the tinted look used in lists.
 */
@Composable
fun MemberAvatar(
    name: String,
    phone: String = "",
    size: Dp = 44.dp,
    tint: Color = GreenDark,
    url: String? = null,
    filled: Boolean = false,
    verified: Boolean? = null
) {
    val key = phone.trim()
    val given = url?.takeIf { it.isNotBlank() }
    // one batched lookup per screen: gives the photo (when the server did not send it) and the verified badge
    LaunchedEffect(key, given, verified) { if (key.isNotEmpty() && (given == null || verified == null)) Avatars.want(key) }
    val isVerified = verified ?: (key.isNotEmpty() && Avatars.verified[key] == true)
    val photo = given ?: Avatars.known[key]?.takeIf { it.isNotEmpty() }
    val bmp by produceState<ImageBitmap?>(null, photo) { value = if (photo == null) null else ImageCache.load(photo) }
    val base = Modifier.size(size).clip(CircleShape)
    Box(Modifier.size(size)) {
        Box(
            if (filled) base.background(Gradient) else base.background(tint.copy(alpha = .12f)),
            contentAlignment = Alignment.Center
        ) {
            val b = bmp
            if (b != null) {
                Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(
                    avatarInitials(name),
                    color = if (filled) Color.White else tint,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.36f).sp
                )
            }
        }
        if (isVerified) {
            // blue "verified" badge on the bottom-right of the picture (identity checked by MudaPay support)
            Image(
                painterResource(R.drawable.verified_badge), "Verified",
                Modifier.size(maxOf(size.value * 0.34f, 13f).dp).align(Alignment.BottomEnd)
            )
        }
    }
}

/**
 * Picks the largest centred square of the chosen picture, straightens it (phone cameras store the turn in EXIF),
 * shrinks it to 512 px and returns it as JPEG. The server makes the final 256 px version.
 */
fun readAvatarForUpload(ctx: Context, uri: Uri): ByteArray? {
    return try {
        // 1) copy to a temp file: needed to read the EXIF rotation on API 23 and to decode twice
        val tmp = File(ctx.cacheDir, "avatar_pick.tmp")
        ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { tmp.delete(); return null }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
        var bmp = BitmapFactory.decodeFile(tmp.path, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp == null) { tmp.delete(); return null }

        // 2) EXIF rotation
        val degrees = try {
            when (ExifInterface(tmp.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        tmp.delete()
        if (degrees != 0f) {
            val m = Matrix().apply { postRotate(degrees) }
            val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (turned !== bmp) bmp.recycle()
            bmp = turned
        }

        // 3) centre square, at most 512 px
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val out = if (side > 512) Bitmap.createScaledBitmap(square, 512, 512, true) else square
        val bytes = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 88, bytes)
        bytes.toByteArray()
    } catch (e: Exception) { null }
}

/** "Profile photo" popup: preview, choose a picture from the phone, or remove the current one. */
@Composable
fun ProfilePhotoDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val name = Session.user?.str("full_name").orEmpty()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { readAvatarForUpload(ctx, uri) }
                if (bytes == null) {
                    busy = false
                    Ui.notify("Profile photo", "This picture could not be read. Choose another one.")
                    return@launch
                }
                val r = Api.upload("/account/avatar", Upload("file", "avatar.jpg", "image/jpeg", bytes))
                busy = false
                if (r.ok) {
                    Avatars.setMine(r.data.str("avatar_url"))
                    Ui.notify("Profile photo", "Your photo was updated.")
                    onDismiss()
                } else {
                    Ui.notify("Profile photo", r.message)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Panel,
        title = { Text(L("Profile photo"), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MemberAvatar(name, size = 112.dp, url = Avatars.mine, filled = true, verified = Avatars.mineVerified)
                Text(
                    L("Members who send you money, or who you ask for money, see this photo next to your name."),
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center
                )
                PrimaryButton(L(if (Avatars.mine == null) "CHOOSE A PHOTO" else "CHANGE PHOTO"), { picker.launch("image/*") }, loading = busy, icon = MudaIcons.PhotoLibrary)
                if (Avatars.mine != null && !busy) {
                    GhostButton(L("REMOVE PHOTO"), {
                        busy = true
                        scope.launch {
                            val r = Api.call("POST", "/account/avatar/remove")
                            busy = false
                            if (r.ok) { Avatars.setMine(null); onDismiss() } else Ui.notify("Profile photo", r.message)
                        }
                    }, Modifier.fillMaxWidth(), icon = MudaIcons.DeleteOutline)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(L("Close"), color = Muted, fontWeight = FontWeight.SemiBold) } }
    )
}

/** The member's own picture with a small camera badge. [onClick] is usually "open [ProfilePhotoDialog]". */
@Composable
fun MyAvatarWithBadge(name: String, size: Dp = 64.dp, onClick: () -> Unit = {}) {
    Box(Modifier.size(size + 6.dp)) {
        Box(Modifier.clip(CircleShape).clickable(onClick = onClick)) { MemberAvatar(name, size = size, url = Avatars.mine, filled = true, verified = Avatars.mineVerified) }
        Box(
            Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(Panel).padding(2.dp).clip(CircleShape).background(Green).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(MudaIcons.CameraAlt, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
    }
}

package io.mudapay.mobile

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.navigation.material.BottomSheetNavigator
import com.google.accompanist.navigation.material.ExperimentalMaterialNavigationApi
import com.google.accompanist.navigation.material.ModalBottomSheetLayout
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.android.relay.ConnectionType
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal
import com.reown.appkit.client.models.request.Request as AppKitRequest
import com.reown.appkit.presets.AppKitChainsPresets
import com.reown.appkit.ui.appKitGraph
import com.reown.appkit.ui.openAppKit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * "Connect external wallet": WalletConnect (Reown AppKit) so a member can approve MudaPay payments inside MetaMask, Trust Wallet,
 * Rainbow ... instead of the built-in wallet. BNB Chain only (AppKit for Android supports EVM chains only).
 *
 * MudaPay never receives the external wallet's keys: it only learns the PUBLIC address while the wallet is connected, builds the payment,
 * and the other wallet app shows it and signs it. This file is deliberately self-contained: if the SDK does not compile against these
 * calls, delete this file, the "ExternalWallet" route in MainActivity.kt and the row in WalletScreens.kt (onExternal), plus the Reown lines in app/build.gradle.kts.
 *
 * To read the connected address and network the code uses only AppKit.getAccount() / AppKit.getSelectedChain() and their text form,
 * so it does not depend on field names that may change between SDK versions. If a value cannot be read, the screen refuses to send
 * (it never guesses the network).
 */
object ExternalWallet {
    /** Public Reown project id (dashboard.reown.com). It is not a secret; replace it here after you regenerate it. */
    const val PROJECT_ID = "7282b65b3916912ce0c9f0577bd0f06e"
    /** Deep link the wallet app uses to return to MudaPay (registered in AndroidManifest.xml). */
    const val REDIRECT = "mudapay-wc://request"

    @Volatile var ready = false
    @Volatile var started = false
    @Volatile var initError: String? = null

    /** What the wallet app answered to the last payment request: the transaction hash (approved) or a rejection. Read by the screen. */
    var lastTxHash by mutableStateOf<String?>(null)
    var lastRejected by mutableStateOf(false)
    fun clearResult() { lastTxHash = null; lastRejected = false }

    private val HASH_RX = Regex("0x[0-9a-fA-F]{64}")

    // ---- RESULT CAPTURE (optional) ------------------------------------------------------------------------------------
    // The wallet app answers a payment request with the transaction hash. This listener catches that answer (read from the
    // SDK's text form, so it does not depend on field names). UNCONFIRMED against the real SDK: if the compiler complains about
    // this block (the member list of AppKit.ModalDelegate differs between versions), fix the member names it reports, or delete
    // the block and the "AppKit.setDelegate(resultDelegate)" line. The screen then simply asks the member to paste the hash.
    private val resultDelegate = object : AppKit.ModalDelegate {
        override fun onSessionApproved(approvedSession: Modal.Model.ApprovedSession) {}
        override fun onSessionRejected(rejectedSession: Modal.Model.RejectedSession) {}
        override fun onSessionUpdate(updatedSession: Modal.Model.UpdatedSession) {}
        override fun onSessionExtend(session: Modal.Model.Session) {}
        override fun onSessionEvent(sessionEvent: Modal.Model.SessionEvent) {}
        override fun onSessionDelete(deletedSession: Modal.Model.DeletedSession) {}
        override fun onSessionRequestResponse(response: Modal.Model.SessionRequestResponse) {
            val hash = HASH_RX.find(response.toString())?.value
            if (hash != null) lastTxHash = hash else lastRejected = true
        }
        override fun onProposalExpired(proposal: Modal.Model.ExpiredProposal) {}
        override fun onRequestExpired(request: Modal.Model.ExpiredRequest) {}
        override fun onConnectionStateChange(state: Modal.Model.ConnectionState) {}
        override fun onError(error: Modal.Model.Error) {}
    }

    /** Called when the member opens "Connect external wallet" (not at app start: nothing contacts Reown for members who never use it). */
    fun init(app: Application) {
        if (started) return
        started = true
        try {
            val meta = Core.Model.AppMetaData(
                name = "MudaPay",
                description = "MudaPay Crypto Wallet",
                url = Config.SITE,
                icons = listOf(Config.SITE + "/icon.png"),
                redirect = REDIRECT
            )
            CoreClient.initialize(projectId = PROJECT_ID, connectionType = ConnectionType.AUTOMATIC, application = app, metaData = meta) { error -> initError = error.toString(); started = false }
            AppKit.initialize(
                init = Modal.Params.Init(CoreClient),
                onSuccess = {
                    AppKit.setChains(AppKitChainsPresets.ethChains.values.toList())
                    AppKit.setDelegate(resultDelegate)
                    ready = true
                },
                onError = { error -> initError = error.toString(); started = false }
            )
        } catch (t: Throwable) {
            initError = t.message ?: "Could not start WalletConnect."; started = false
        }
    }

    private val ADDRESS_RX = Regex("0x[0-9a-fA-F]{40}")
    private val BNB_RX = Regex("(eip155:56|chainReference=56)(?![0-9])")

    /** The connected wallet's address, or null when no wallet is connected (or it cannot be read). */
    fun address(): String? = try { AppKit.getAccount()?.toString()?.let { ADDRESS_RX.find(it)?.value } } catch (t: Throwable) { null }

    /** True only when the SDK clearly reports BNB Chain (chain id 56). Anything unclear counts as "not BNB Chain". */
    fun onBnbChain(): Boolean = try {
        BNB_RX.containsMatchIn((AppKit.getSelectedChain()?.toString() ?: "") + " " + (AppKit.getAccount()?.toString() ?: ""))
    } catch (t: Throwable) { false }
}

@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterialNavigationApi::class)
@Composable
fun ExternalWalletScreen(nav: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { while (!ExternalWallet.ready) { ExternalWallet.init(ctx.applicationContext as Application); delay(4000) } }
    // AppKit shows its wallet picker as a bottom sheet inside its own small navigation graph; it lives inside this screen only,
    // so the rest of the app's navigation is untouched.
    val sheetState = rememberModalBottomSheetState(ModalBottomSheetValue.Hidden, skipHalfExpanded = true)
    val bottomSheetNavigator = remember { BottomSheetNavigator(sheetState) }
    val navController = rememberNavController(bottomSheetNavigator)
    ModalBottomSheetLayout(bottomSheetNavigator = bottomSheetNavigator) {
        NavHost(navController, startDestination = "external_home") {
            composable("external_home") { ExternalWalletHome(navController) }
            appKitGraph(navController)
        }
    }
}

/** A payment sent to the wallet app, waiting for its transaction hash. */
private class ExtPay(val assetId: String, val symbol: String, val from: String, val to: String, val amount: String, val txid: String = "")

@Composable
private fun ExternalWalletHome(navController: NavController) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ExtPay?>(null) }
    var tracked by remember { mutableStateOf<ExtPay?>(null) }
    var hashText by remember { mutableStateOf("") }
    var tracking by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf<String?>(null) }
    var bnb by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    // the SDK keeps the session; read it once a second while this screen is open
    LaunchedEffect(Unit) {
        while (true) { address = ExternalWallet.address(); bnb = ExternalWallet.onBnbChain(); delay(1000) }
    }

    val options = Pinned.assets.filter { it.value.canSend }.map { it.key to "${it.value.symbol} · ${Pinned.chainNames[it.value.chain] ?: it.value.chain}" }
    var assetId by remember { mutableStateOf(options.firstOrNull()?.first ?: "") }
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    val pinned = Pinned.assets[assetId]

    // after the wallet app approved the payment: show Pending / Confirmed / Failed with the hash and the explorer button
    val done = tracked
    if (done != null) {
        WalletTxStatus("bsc", done.txid, done.symbol, done.amount, done.to, "pending", true, onDone = {
            tracked = null; to = ""; amount = ""; info = null; err = null; ExternalWallet.clearResult()
        })
        return
    }

    fun track(pay: ExtPay, hash: String) {
        if (tracking) return
        val h = hash.trim().lowercase()
        if (!Regex("^0x[0-9a-f]{64}$").matches(h)) { err = L("This is not a valid transaction hash."); return }
        tracking = true; err = null
        scope.launch {
            val r = Api.call("POST", "/wallet/track", JSONObject().put("asset", pay.assetId).put("txid", h).put("from", pay.from).put("to", pay.to).put("amount", pay.amount))
            tracking = false
            if (r.ok) { tracked = ExtPay(pay.assetId, pay.symbol, pay.from, pay.to, pay.amount, h); pending = null; hashText = ""; ExternalWallet.clearResult() }
            else err = r.message
        }
    }

    // the wallet app's answer arrives through the SDK listener (see ExternalWallet.resultDelegate)
    val sdkHash = ExternalWallet.lastTxHash
    val sdkRejected = ExternalWallet.lastRejected
    LaunchedEffect(sdkHash, sdkRejected) {
        val p = pending
        if (p != null && sdkHash != null) track(p, sdkHash)
        else if (p != null && sdkRejected) { err = L("The payment was rejected in your wallet app."); info = null; pending = null; ExternalWallet.clearResult() }
    }

    fun connect() {
        err = null; info = null
        if (!ExternalWallet.ready) { err = ExternalWallet.initError ?: L("WalletConnect is still starting. Try again in a moment."); return }
        try { navController.openAppKit(shouldOpenChooseNetwork = true, onError = { e -> err = e.toString() }) }
        catch (t: Throwable) { err = t.message ?: L("Could not open the wallet list.") }
    }

    fun disconnect() {
        err = null; info = null
        try { AppKit.disconnect(onSuccess = { address = null }, onError = { e -> err = e.toString() }) }
        catch (t: Throwable) { err = t.message ?: L("Could not disconnect.") }
    }

    fun send() {
        err = null; info = null
        val from = address
        val p = pinned
        if (from == null || p == null) { err = L("Connect a wallet first."); return }
        if (!ExternalWallet.onBnbChain()) { err = L("The connected wallet must be on BNB Chain. Open the wallet list and choose BNB Chain."); return }
        val dest = to.trim()
        if (!WalletCrypto.isEvmAddress(dest)) { err = L("This is not a valid BNB Chain address."); return }
        val raw = WalletCrypto.toRaw(amount, p.decimals)
        if (raw == null) { err = L("Enter a valid amount."); return }
        val params = if (p.contract == null) {
            """[{"from":"$from","to":"$dest","value":"0x${raw.toString(16)}"}]"""
        } else {
            val data = "0xa9059cbb" + dest.removePrefix("0x").lowercase().padStart(64, '0') + raw.toString(16).padStart(64, '0')
            """[{"from":"$from","to":"${p.contract}","data":"$data","value":"0x0"}]"""
        }
        try {
            ExternalWallet.clearResult()
            val exact = WalletCrypto.fromRaw(raw, p.decimals)
            AppKit.request(
                request = AppKitRequest(method = "eth_sendTransaction", params = params),
                onSuccess = { _ -> info = L("Request sent. Open your wallet app, check the payment and approve it."); pending = ExtPay(assetId, p.symbol, from, dest, exact) },
                onError = { e: Throwable -> err = e.toString() }
            )
        } catch (t: Throwable) { err = t.message ?: L("The request could not be sent.") }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle(L("External wallet"), L("Approve MudaPay payments inside MetaMask, Trust Wallet or another wallet app (BNB Chain)."))
        InfoBanner(L("MudaPay never gets the keys of your other wallet. It only sees the public address while connected, and every payment is approved inside your wallet app."), Blue, MudaIcons.Info)
        err?.let { InfoBanner(it, Danger, MudaIcons.Warning) }
        info?.let { InfoBanner(it, Green, MudaIcons.CheckCircle) }

        val a = address
        if (a == null) {
            PrimaryButton("CONNECT WALLET", { connect() }, icon = MudaIcons.Link)
        } else {
            Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L("Connected wallet"), color = Muted, fontSize = 12.sp)
                Text(a, color = Dark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(if (bnb) L("Network: BNB Chain") else L("Network: not BNB Chain"), color = if (bnb) GreenDark else Danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!bnb) InfoBanner(L("Switch the wallet to BNB Chain to send."), Gold, MudaIcons.Warning)
            if (!bnb) PrimaryButton("CHOOSE BNB CHAIN", { connect() }, color = Dark, icon = MudaIcons.Link)

            SectionTitle(L("Pay from this wallet"))
            Dropdown(L("Asset"), assetId, options) { assetId = it }
            InputField(L("Recipient address") + " (BNB Chain)", to, { to = it; err = null })
            InputField(L("Amount") + " (${pinned?.symbol ?: ""})", amount, { amount = it; err = null }, keyboard = KeyboardType.Decimal)
            Text(L("Payments on the blockchain cannot be undone. Check the address and the network in your wallet app before approving."), color = Muted, fontSize = 12.sp)
            PrimaryButton("SEND FROM THIS WALLET", { send() }, enabled = bnb, icon = MudaIcons.NorthEast)
            pending?.let { pay ->
                Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(L("Waiting for your wallet app"), color = Dark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(L("When you approve the payment, MudaPay shows its status here. If it does not appear, paste the transaction hash from your wallet app."), color = Muted, fontSize = 12.sp)
                    InputField(L("Transaction hash (optional)"), hashText, { hashText = it.trim(); err = null })
                    PrimaryButton(L("TRACK PAYMENT"), { track(pay, hashText) }, enabled = hashText.isNotBlank(), loading = tracking, color = Dark, icon = MudaIcons.Link)
                }
            }
            PrimaryButton("DISCONNECT", { disconnect() }, color = Dark, icon = MudaIcons.Close)
        }
        Spacer(Modifier.height(8.dp))
    }
}

package io.mudapay.mobile

import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.MnemonicUtils
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionEncoder
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * MudaPay Crypto Wallet: all key work happens HERE, on the phone. Nothing in this file talks to the network.
 *
 *  - Recovery phrase: BIP-39, 12 words (128 bits from SecureRandom), no passphrase.
 *  - BNB Chain (EVM): m/44'/60'/0'/0/0, address 0x...  (same address as MetaMask / Trust Wallet for the same phrase)
 *  - Tron:            m/44'/195'/0'/0/0, address T...  (same address as TronLink / Trust Wallet for the same phrase)
 *
 * The server only ever receives the two PUBLIC addresses and finished, signed transactions.
 */

/** One asset of the wallet, as described by GET /wallet/config. */
class WalletAsset(val id: String, val chain: String, val symbol: String, val name: String, val decimals: Int, val contract: String?, val canSend: Boolean)

/**
 * What each asset REALLY is. These values are fixed inside the app: the server may only switch an asset on or off, it can never change the
 * contract, the decimals or the network of what the phone signs (a hostile or hacked server must not be able to turn "10 USDT" into "10 BNB").
 */
class PinnedAsset(val chain: String, val symbol: String, val decimals: Int, val contract: String?, val canSend: Boolean)

object Pinned {
    val assets: Map<String, PinnedAsset> = mapOf(
        "bnb" to PinnedAsset("bsc", "BNB", 18, null, true),
        "usdt_bep20" to PinnedAsset("bsc", "USDT", 18, "0x55d398326f99059fF775485246999027B3197955", true)
        // Tron is switched OFF (BNB Chain only). Its code below stays dormant: to switch it on again add back
        //   "usdt_trc20" to PinnedAsset("tron", "USDT", 6, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", true), "trx" to PinnedAsset("tron", "TRX", 6, null, false)
        // and "tron" to ... in explorers (https://tronscan.org/#/transaction/) and chainNames ("Tron"). Every signer refuses assets that are not pinned.
    )
    val explorers: Map<String, String> = mapOf("bsc" to "https://bscscan.com/tx/")
    val chainNames: Map<String, String> = mapOf("bsc" to "BNB Chain")

    fun matches(a: WalletAsset): Boolean {
        val p = assets[a.id] ?: return false
        return p.chain == a.chain && p.symbol == a.symbol && p.decimals == a.decimals && (p.contract ?: "").equals(a.contract ?: "", ignoreCase = true)
    }
}

class WalletAddresses(val bsc: String, val tron: String) {
    fun forChain(chain: String): String = if (chain == "tron") tron else bsc
}

/** Signing context for BNB Chain, as returned by POST /wallet/prepare. */
class EvmPlan(val chainId: Long, val nonce: BigInteger, val gasPrice: BigInteger, val gasLimit: BigInteger)

/** Signing context for Tron, as returned by POST /wallet/prepare. */
class TronPlan(val refBlockBytes: ByteArray, val refBlockHash: ByteArray, val timestampMs: Long, val expirationMs: Long, val feeLimitSun: Long)

class SignedTx(val hex: String, val txid: String)

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(input: ByteArray): String {
        var n = BigInteger(1, input)
        val sb = StringBuilder()
        while (n.signum() > 0) {
            val qr = n.divideAndRemainder(BASE)
            sb.append(ALPHABET[qr[1].toInt()])
            n = qr[0]
        }
        for (b in input) { if (b.toInt() == 0) sb.append(ALPHABET[0]) else break }
        return sb.reverse().toString()
    }

    fun decode(s: String): ByteArray? {
        if (s.isEmpty()) return null
        var n = BigInteger.ZERO
        for (c in s) {
            val d = ALPHABET.indexOf(c)
            if (d < 0) return null
            n = n.multiply(BASE).add(BigInteger.valueOf(d.toLong()))
        }
        var bytes = n.toByteArray()
        if (bytes.size > 1 && bytes[0].toInt() == 0) bytes = bytes.copyOfRange(1, bytes.size)   // BigInteger sign byte
        if (n.signum() == 0) bytes = ByteArray(0)
        var zeros = 0
        for (c in s) { if (c == ALPHABET[0]) zeros++ else break }
        return ByteArray(zeros) + bytes
    }

    private fun checksum(payload: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(payload)).copyOf(4)
    }

    fun encodeCheck(payload: ByteArray): String = encode(payload + checksum(payload))

    /** The payload without its 4 checksum bytes, or null when the text is not valid Base58Check. */
    fun decodeCheck(s: String): ByteArray? {
        val all = decode(s) ?: return null
        if (all.size < 5) return null
        val payload = all.copyOfRange(0, all.size - 4)
        val sum = all.copyOfRange(all.size - 4, all.size)
        return if (checksum(payload).contentEquals(sum)) payload else null
    }
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) { sb.append("0123456789abcdef"[(b.toInt() shr 4) and 0xF]); sb.append("0123456789abcdef"[b.toInt() and 0xF]) }
    return sb.toString()
}

fun hexToBytes(s0: String): ByteArray? {
    val s = s0.removePrefix("0x").removePrefix("0X")
    if (s.length % 2 != 0) return null
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(s[i * 2], 16); val lo = Character.digit(s[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

object WalletCrypto {
    private val H = Bip32ECKeyPair.HARDENED_BIT
    private val BSC_PATH = intArrayOf(44 or H, 60 or H, 0 or H, 0, 0)
    private val TRON_PATH = intArrayOf(44 or H, 195 or H, 0 or H, 0, 0)
    private const val TRON_TRIGGER_URL = "type.googleapis.com/protocol.TriggerSmartContract"
    private const val TRANSFER_SELECTOR = "a9059cbb"   // transfer(address,uint256)

    /* ------------------------------------------------------------ phrase */
    fun newMnemonic(): String {
        val entropy = ByteArray(16)
        SecureRandom().nextBytes(entropy)
        return MnemonicUtils.generateMnemonic(entropy)
    }

    fun normalize(s: String): String = s.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    fun isValidMnemonic(s: String): Boolean {
        val n = normalize(s)
        val count = if (n.isEmpty()) 0 else n.split(" ").size
        if (count != 12 && count != 24) return false
        return try { MnemonicUtils.validateMnemonic(n) } catch (e: Exception) { false }
    }

    /* ------------------------------------------------------------ keys + addresses */
    private fun keyPair(mnemonic: String, path: IntArray): ECKeyPair {
        val seed = MnemonicUtils.generateSeed(normalize(mnemonic), "")
        val master = Bip32ECKeyPair.generateKeyPair(seed)
        return Bip32ECKeyPair.deriveKeyPair(master, path)
    }

    private fun pad(v: BigInteger, size: Int): ByteArray {
        val raw = v.toByteArray()
        val trimmed = if (raw.size > size && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        require(trimmed.size <= size) { "number too large" }
        return ByteArray(size - trimmed.size) + trimmed
    }

    private fun bscAddressOf(kp: ECKeyPair): String = Keys.toChecksumAddress(Keys.getAddress(kp.publicKey))

    private fun tronBytesOf(kp: ECKeyPair): ByteArray {
        val hash = Hash.sha3(pad(kp.publicKey, 64))   // Keccak-256 of the 64-byte public key
        return byteArrayOf(0x41) + hash.copyOfRange(12, 32)
    }

    fun addressesOf(mnemonic: String): WalletAddresses {
        val bsc = bscAddressOf(keyPair(mnemonic, BSC_PATH))
        val tron = Base58.encodeCheck(tronBytesOf(keyPair(mnemonic, TRON_PATH)))
        return WalletAddresses(bsc, tron)
    }

    /* ------------------------------------------------------------ address checks */
    private val EVM_RX = Regex("^0x[0-9a-fA-F]{40}$")
    /** 0x + 40 hex. An address written in MIXED case must also carry the right EIP-55 checksum (catches typos); all-lower / all-upper is accepted. */
    fun isEvmAddress(a: String): Boolean {
        if (!EVM_RX.matches(a)) return false
        val body = a.substring(2)
        val mixed = body.any { it in 'a'..'f' } && body.any { it in 'A'..'F' }
        return !mixed || Keys.toChecksumAddress(a).removePrefix("0x").removePrefix("0X") == body
    }
    fun tronBytes(a: String): ByteArray? {
        val p = Base58.decodeCheck(a) ?: return null
        return if (p.size == 21 && p[0].toInt() == 0x41) p else null
    }
    fun isTronAddress(a: String) = tronBytes(a) != null
    fun isAddressFor(chain: String, a: String) = if (chain == "tron") isTronAddress(a) else isEvmAddress(a)

    /* ------------------------------------------------------------ amounts */
    /** "12.5" -> raw base units, or null when it is not a positive number or has more decimals than the asset allows. */
    fun toRaw(human: String, decimals: Int): BigInteger? {
        val t = human.trim().replace(',', '.')
        if (!Regex("^\\d+(\\.\\d+)?$").matches(t)) return null
        val bd = BigDecimal(t).stripTrailingZeros()
        if (bd.scale() > decimals) return null
        val raw = bd.movePointRight(decimals).toBigInteger()
        return if (raw.signum() > 0) raw else null
    }

    fun fromRaw(raw: BigInteger, decimals: Int): String {
        val s = BigDecimal(raw).movePointLeft(decimals).stripTrailingZeros().toPlainString()
        return s
    }

    /* ------------------------------------------------------------ BNB Chain (EVM) */
    /** Signs a BNB transfer or a BEP20 (USDT) transfer. Returns the raw transaction as hex (0x...) and its hash. */
    fun signBsc(mnemonic: String, asset: WalletAsset, from: String, to: String, raw: BigInteger, plan: EvmPlan): SignedTx {
        require(Pinned.matches(asset) && asset.chain == "bsc" && asset.canSend) { "Unknown asset." }
        require(raw.signum() > 0 && raw.bitLength() <= 256) { "Invalid amount." }
        require(plan.gasPrice.signum() > 0 && plan.gasLimit >= BigInteger.valueOf(21000)) { "Invalid network fee." }
        val kp = keyPair(mnemonic, BSC_PATH)
        require(bscAddressOf(kp).equals(from, ignoreCase = true)) { "This wallet does not own the sending address." }
        require(isEvmAddress(to)) { "Invalid address." }
        require(plan.chainId == 56L) { "Unexpected network." }
        val tx = if (asset.contract == null) {
            RawTransaction.createTransaction(plan.nonce, plan.gasPrice, plan.gasLimit, to, raw, "")
        } else {
            val data = "0x" + TRANSFER_SELECTOR + to.removePrefix("0x").lowercase().padStart(64, '0') + raw.toString(16).padStart(64, '0')
            RawTransaction.createTransaction(plan.nonce, plan.gasPrice, plan.gasLimit, asset.contract, BigInteger.ZERO, data)
        }
        val signed = TransactionEncoder.signMessage(tx, plan.chainId, Credentials.create(kp))
        return SignedTx("0x" + signed.toHex(), "0x" + Hash.sha3(signed).toHex())
    }

    /* ------------------------------------------------------------ Tron */
    private class Pb {
        val out = ByteArrayOutputStream()
        private fun varint(v0: Long) {
            var v = v0
            while ((v and 0x7FL.inv()) != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
            out.write(v.toInt())
        }
        private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun bytes(field: Int, data: ByteArray): Pb { tag(field, 2); varint(data.size.toLong()); out.write(data); return this }
        fun num(field: Int, v: Long): Pb { tag(field, 0); varint(v); return this }
        fun build(): ByteArray = out.toByteArray()
    }

    /**
     * Builds, hashes and signs a TRC20 transfer (USDT) ON THE PHONE. The server only supplied the reference block and fee limit;
     * the destination, the amount and the contract are encoded here, so the server cannot change them.
     * The result is the full Transaction protobuf (raw_data + signature) for /wallet/broadcasthex.
     */
    fun signTronTrc20(mnemonic: String, asset: WalletAsset, from: String, to: String, raw: BigInteger, plan: TronPlan): SignedTx {
        require(Pinned.matches(asset) && asset.chain == "tron" && asset.contract != null && asset.canSend) { "Unknown asset." }
        require(raw.signum() > 0 && raw.bitLength() <= 256) { "Invalid amount." }
        val kp = keyPair(mnemonic, TRON_PATH)
        val owner = tronBytesOf(kp)
        require(Base58.encodeCheck(owner) == from) { "This wallet does not own the sending address." }
        val toBytes = tronBytes(to) ?: throw IllegalArgumentException("Invalid address.")
        val contract = tronBytes(asset.contract ?: "") ?: throw IllegalArgumentException("Invalid token.")
        require(plan.refBlockBytes.size == 2 && plan.refBlockHash.size == 8) { "Invalid block reference." }
        // do not trust the server blindly: a bad expiry or an enormous fee limit could burn the member's TRX
        require(plan.expirationMs > plan.timestampMs && plan.expirationMs - plan.timestampMs <= 3_600_000L) { "Invalid expiry." }
        val now = System.currentTimeMillis()   // a signed transaction must not be usable much later than the member confirmed it
        require(Math.abs(plan.timestampMs - now) <= 120_000L && plan.expirationMs <= now + 3_600_000L) { "The network data is out of date. Check that your phone's date and time are correct, then try again." }
        require(plan.feeLimitSun in 1_000_000L..100_000_000L) { "Fee limit out of range." }

        val data = hexToBytes(TRANSFER_SELECTOR + toBytes.copyOfRange(1, 21).toHex().padStart(64, '0') + raw.toString(16).padStart(64, '0'))!!
        val trigger = Pb().bytes(1, owner).bytes(2, contract).bytes(4, data).build()
        val any = Pb().bytes(1, TRON_TRIGGER_URL.toByteArray(Charsets.US_ASCII)).bytes(2, trigger).build()
        val contractMsg = Pb().num(1, 31).bytes(2, any).build()   // 31 = TriggerSmartContract
        val rawData = Pb()
            .bytes(1, plan.refBlockBytes)
            .bytes(4, plan.refBlockHash)
            .num(8, plan.expirationMs)
            .bytes(11, contractMsg)
            .num(14, plan.timestampMs)
            .num(18, plan.feeLimitSun)
            .build()
        val txid = MessageDigest.getInstance("SHA-256").digest(rawData)

        val sig = kp.sign(txid)   // canonical (low-S) ECDSA over the 32-byte hash
        var recId = -1
        for (i in 0..3) {
            val pub = Sign.recoverFromSignature(i, sig, txid)
            if (pub != null && pub == kp.publicKey) { recId = i; break }
        }
        check(recId in 0..1) { "Could not sign." }
        val signature = pad(sig.r, 32) + pad(sig.s, 32) + byteArrayOf(recId.toByte())
        val tx = Pb().bytes(1, rawData).bytes(2, signature).build()
        return SignedTx(tx.toHex(), txid.toHex())
    }
}

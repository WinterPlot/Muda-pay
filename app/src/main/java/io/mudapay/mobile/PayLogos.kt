package io.mudapay.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp

/**
 * Logos of the mobile-money brands (Lumicash, Airtel, Orange, MTN MoMo, M-Pesa, Moov, Wave, Ecocash, AfriMoney, OPay, PalmPay, Telecel, AirtelTigo) (res/drawable-nodpi/pm_*.png, 192 px squares).
 * A logo is only drawn when the server offers that method to the member, because every screen builds its list from the server's answer.
 */
fun payLogoRes(name: String): Int? {
    val n = name.lowercase()
    return when {
        n.contains("lumicash") -> R.drawable.pm_lumicash
        n.contains("airteltigo") -> R.drawable.pm_airteltigo      // before "airtel": it is a different brand
        n.contains("smartcash") -> R.drawable.pm_smartcash       // Airtel SmartCash (Ghana), before "airtel"
        n.contains("airtel") -> R.drawable.pm_airtel
        n.contains("orange") -> R.drawable.pm_orange
        n.contains("mtn") -> R.drawable.pm_mtn
        n.contains("m-pesa") || n.contains("mpesa") -> R.drawable.pm_mpesa
        n.contains("moov") -> R.drawable.pm_moov
        n.contains("wave") -> R.drawable.pm_wave
        n.contains("ecocash") -> R.drawable.pm_ecocash
        n.contains("afrimoney") -> R.drawable.pm_afrimoney
        n.contains("opay") -> R.drawable.pm_opay
        n.contains("palmpay") -> R.drawable.pm_palmpay
        n.contains("telecel") -> R.drawable.pm_telecel
        n.contains("multicaixa") -> R.drawable.pm_multicaixa
        n == "card" || n.contains("visa") -> R.drawable.pm_card        // Visa + Mastercard
        n.startsWith("usdt") -> R.drawable.pm_usdt
        n.contains("bank") || n == "eft" -> R.drawable.pm_bank           // Bank Transfer (Nigeria), EFT (South Africa)
        n.contains("tigo pesa") || n.contains("tigopesa") || n.contains("mixx") -> R.drawable.pm_tigopesa   // Mixx by Yas is the new name of Tigo Pesa
        n.contains("halopesa") -> R.drawable.pm_halopesa
        n.contains("zamtel") -> R.drawable.pm_zamtel
        n.contains("mola") -> R.drawable.pm_emola
        n.contains("paypay") -> R.drawable.pm_paypay
        n.contains("paypal") -> R.drawable.pm_paypal        // PayPal (UK / USA and every country where PayPal deposits are on)
        else -> null
    }
}

@Composable
fun PayLogoImage(res: Int, size: Dp) {
    Image(painterResource(res), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)))
}

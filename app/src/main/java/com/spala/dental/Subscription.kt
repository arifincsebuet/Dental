package com.spala.dental

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.billingclient.api.*
import com.spala.dental.ui.theme.*
import java.security.MessageDigest

/*
 * Clinic subscription through Google Play Billing.
 *
 * Pricing is configured in Play Console, never in code:
 *   product  dental_care_pro            (BuildConfig.SUBSCRIPTION_PRODUCT_ID)
 *   base plan "monthly"  auto-renewing, 1 month, USD 10.00
 *   offer     "launch"   discounted phase USD 5.00 / month (tag: launch)
 * The app shows whatever offers Play returns for this user, so ending the $5 offer in Play Console
 * switches new subscribers to $10 without an app update. One subscription covers the whole clinic;
 * patients are never charged.
 */

/** A purchasable plan as Play reports it, already formatted for the device locale and currency. */
data class PlanOption(
    val details: ProductDetails, val offerToken: String, val offerId: String?,
    val introPrice: String?, val introCycles: Int, val regularPrice: String, val period: String,
)

class BillingManager(private val context: Context, private val onPurchase: (Purchase) -> Unit) : PurchasesUpdatedListener {
    var plan by mutableStateOf<PlanOption?>(null); private set
    var status by mutableStateOf("Connecting to Google Play…"); private set
    /** True when this device's Play account holds an active purchase (used for offline/local mode). */
    var ownedOnDevice by mutableStateOf(false); private set
    private val productId = BuildConfig.SUBSCRIPTION_PRODUCT_ID

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun connect() {
        if (client.isReady) { load(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) load()
                else status = "Google Play Billing is unavailable (${result.debugMessage.ifBlank { "code ${result.responseCode}" }})"
            }
            override fun onBillingServiceDisconnected() { status = "Disconnected from Google Play. Reopen to retry." }
        })
    }

    private fun load() {
        val query = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(BillingClient.ProductType.SUBS).build())).build()
        client.queryProductDetailsAsync(query) { result, details ->
            val product = details.productDetailsList.firstOrNull()
            plan = if (result.responseCode == BillingClient.BillingResponseCode.OK && product != null) bestOffer(product) else null
            status = if (plan == null) "The subscription is not available yet. Check that the app was installed from Google Play." else ""
        }
        restore()
    }

    /** Re-reads active purchases (Restore purchase). Unverified purchases are handed to the clinic server. */
    fun restore() {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val active = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && productId in it.products }
            ownedOnDevice = active.isNotEmpty()
            active.forEach(onPurchase)
        }
    }

    /** Eligible offers come first from Play; prefer the one with the cheapest first payment (the launch offer). */
    private fun bestOffer(product: ProductDetails): PlanOption? {
        val offers = product.subscriptionOfferDetails.orEmpty()
        val chosen = offers.minByOrNull { offer -> offer.pricingPhases.pricingPhaseList.firstOrNull()?.priceAmountMicros ?: Long.MAX_VALUE } ?: return null
        val phases = chosen.pricingPhases.pricingPhaseList
        val regular = phases.last()
        val intro = phases.takeIf { it.size > 1 }?.first()
        return PlanOption(product, chosen.offerToken, chosen.offerId, intro?.formattedPrice, intro?.billingCycleCount ?: 0, regular.formattedPrice, periodName(regular.billingPeriod))
    }

    private fun periodName(iso: String) = when (iso) { "P1M" -> "month"; "P1Y" -> "year"; "P1W" -> "week"; "P3M" -> "3 months"; "P6M" -> "6 months"; else -> iso }

    fun purchase(activity: Activity, clinicId: String) {
        val option = plan ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(option.details).setOfferToken(option.offerToken).build()))
            // Ties the purchase to this clinic so the server can refuse tokens meant for another clinic.
            .setObfuscatedAccountId(accountTag(clinicId))
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) status = "Could not open Google Play checkout (${result.responseCode})"
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { purchase ->
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) { ownedOnDevice = true; onPurchase(purchase) }
                else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) status = "Payment pending. Access starts when Google Play confirms it."
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> status = ""
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            else -> status = "Purchase failed: ${result.debugMessage.ifBlank { "code ${result.responseCode}" }}"
        }
    }

    /** Offline mode has no server, so acknowledge on the device (required within 3 days). */
    fun acknowledgeLocally(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { }
    }

    fun manageUrl() = "https://play.google.com/store/account/subscriptions?sku=$productId&package=${context.packageName}"

    companion object {
        fun accountTag(clinicId: String): String =
            MessageDigest.getInstance("SHA-256").digest("dental-care:$clinicId".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

// ---------------------------------------------------------------------------------------------
// Paywall
// ---------------------------------------------------------------------------------------------

@Composable
fun PaywallScreen(store: ClinicStore, billing: BillingManager) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { billing.connect() }
    val canBuy = store.currentRole in listOf("owner", "admin", "administrator")
    val plan = billing.plan
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(HeroTop, AppBackground))).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        DentalAppLogo(Modifier.size(64.dp))
        Text("Dental Care Pro", style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text("One subscription for your whole clinic: every doctor and staff member. Patients use their app access free of charge.",
            style = MaterialTheme.typography.bodyMedium, color = InkMuted, textAlign = TextAlign.Center)
        SurfaceCard {
            listOf("Interactive 3D dental charts and treatment history", "Patients, appointments, billing and reports",
                "Secure cloud sync and backup for your team", "Patient app logins for read-only access").forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Teal50), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Check, Teal700, 14.dp) }
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = Ink)
                }
            }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.background(Brush.linearGradient(listOf(Teal600, HeroDeep))).padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plan == null) {
                    Text(billing.status.ifBlank { "Loading price…" }, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                } else {
                    if (plan.introPrice != null) {
                        Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .18f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("LAUNCH OFFER", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(plan.introPrice, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                            Text("/ ${plan.period}", color = Color.White.copy(alpha = .85f), modifier = Modifier.padding(bottom = 8.dp))
                            Text(plan.regularPrice, color = Color.White.copy(alpha = .7f), textDecoration = TextDecoration.LineThrough, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Text(if (plan.introCycles > 0) "for the first ${plan.introCycles} ${plan.period}${if (plan.introCycles > 1) "s" else ""}, then ${plan.regularPrice} / ${plan.period}" else "then ${plan.regularPrice} / ${plan.period}",
                            color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(plan.regularPrice, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                            Text("/ ${plan.period}", color = Color.White.copy(alpha = .85f), modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }
                    Text("Renews automatically. Cancel anytime in Google Play.", color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (canBuy) Button(
            onClick = { (context as? Activity)?.let { billing.purchase(it, store.subscriptionClinicId) } },
            enabled = plan != null && !store.busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
        ) { Text(if (plan?.introPrice != null) "Subscribe for ${plan.introPrice}" else "Subscribe") }
        else SurfaceCard(background = SurfaceTint) {
            Text("Your clinic's subscription is not active", style = MaterialTheme.typography.titleSmall, color = Ink)
            Text("Ask the clinic owner or administrator to subscribe. You'll get access automatically.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { billing.restore(); store.refreshSubscription() }, shape = RoundedCornerShape(14.dp)) { Text("Restore purchase") }
            OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(billing.manageUrl()))) }, shape = RoundedCornerShape(14.dp)) { Text("Manage") }
        }
        if (plan != null && billing.status.isNotBlank()) Text(billing.status, color = InkMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        if (store.message.isNotBlank()) Text(store.message, color = InkMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        TextButton(onClick = { if (store.session == "local") store.leaveLocalMode() else store.signOut() }) { Text("Sign out") }
    }
}

/** Settings card: current plan state and a link to Google Play's subscription management. */
@Composable
fun SubscriptionCard(store: ClinicStore, billing: BillingManager) {
    val context = LocalContext.current
    val sub = store.subscription
    SurfaceCard {
        Text("Subscription", style = MaterialTheme.typography.titleSmall, color = Ink)
        Text(when {
            store.isSuperAdmin -> "Super admin · free access, no subscription required."
            sub.optBoolean("complimentary") -> "Complimentary access (clinic owned by a super admin)."
            BuildConfig.DEBUG && !store.subscriptionActive -> "Debug build: subscription check bypassed for testing."
            store.subscriptionActive && sub.optLong("expiresAt") > 0 -> "Dental Care Pro · active until ${java.text.DateFormat.getDateInstance().format(java.util.Date(sub.optLong("expiresAt")))}" +
                if (sub.optBoolean("autoRenewing", true)) " (renews automatically)" else " (will not renew)"
            store.subscriptionActive -> "Dental Care Pro · active"
            else -> "Not subscribed"
        }, style = MaterialTheme.typography.bodySmall, color = InkMuted)
        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(billing.manageUrl()))) }, shape = RoundedCornerShape(14.dp)) { Text("Manage in Google Play") }
    }
}

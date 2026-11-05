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
 * Clinic subscription through Google Play Billing, priced by the clinic's number of patients.
 *
 *   Patients            Launch offer      Regular price
 *   up to 1,000         USD  5 / month    USD 10 / month     product dental_care_pro
 *   1,001 – 2,000       USD 10 / month    USD 20 / month     product dental_care_pro_t2
 *   2,001 – 3,000       USD 15 / month    USD 30 / month     product dental_care_pro_t3
 *   … each further 1,000 patients adds USD 5 (offer) / USD 10 (regular), up to PlanTiers.MAX_TIER.
 *
 * Prices are configured in Play Console, never charged from code: each tier is its own
 * subscription product with base plan "monthly" (regular price) and offer "launch" (offer price,
 * tag: launch). The app shows whatever Play returns, so ending the launch offers in Play Console
 * switches new subscribers to the regular price without an app update. One subscription covers the
 * whole clinic; patients are never charged. When the clinic grows past its tier, the admin upgrades
 * in the app (Google Play prorates the change).
 */

object PlanTiers {
    const val PATIENTS_PER_TIER = 1000
    const val MAX_TIER = 10
    const val OFFER_USD_PER_TIER = 5
    const val REGULAR_USD_PER_TIER = 10
    private val base get() = BuildConfig.SUBSCRIPTION_PRODUCT_ID
    fun productId(tier: Int) = if (tier <= 1) base else "${base}_t$tier"
    /** Tier of a Play product id (0 if it is not one of ours). */
    fun tierOf(productId: String): Int = when {
        productId == base -> 1
        productId.startsWith("${base}_t") -> productId.removePrefix("${base}_t").toIntOrNull()?.takeIf { it in 2..MAX_TIER } ?: 0
        else -> 0
    }
    /** The tier a clinic with [patients] active patients needs (at least 1). */
    fun requiredTier(patients: Int) = ((patients + PATIENTS_PER_TIER - 1) / PATIENTS_PER_TIER).coerceIn(1, MAX_TIER)
    fun limit(tier: Int) = tier * PATIENTS_PER_TIER
    fun rangeText(tier: Int) = if (tier <= 1) "Up to 1,000 patients" else "%,d – %,d patients".format(limit(tier - 1) + 1, limit(tier))
    /** Fallback text when Play prices are not loaded yet. */
    fun priceText(tier: Int) = "US$${OFFER_USD_PER_TIER * tier} / month (normally US$${REGULAR_USD_PER_TIER * tier})"
}

/** A purchasable plan as Play reports it, already formatted for the device locale and currency. */
data class PlanOption(
    val details: ProductDetails, val offerToken: String, val offerId: String?,
    val introPrice: String?, val introCycles: Int, val regularPrice: String, val period: String, val tier: Int,
)

class BillingManager(private val context: Context, private val onPurchase: (Purchase) -> Unit) : PurchasesUpdatedListener {
    /** Plans by tier (1 = up to 1,000 patients), as loaded from Play. */
    var plans by mutableStateOf<Map<Int, PlanOption>>(emptyMap()); private set
    /** The tier-1 plan (kept for older callers). */
    val plan: PlanOption? get() = plans[1]
    var status by mutableStateOf("Connecting to Google Play…"); private set
    /** True when this device's Play account holds an active purchase (used for offline/local mode). */
    var ownedOnDevice by mutableStateOf(false); private set
    /** Highest-tier active purchase on this device's Play account (needed to upgrade it). */
    var ownedPurchase by mutableStateOf<Purchase?>(null); private set
    val ownedTier: Int get() = ownedPurchase?.products?.maxOfOrNull { PlanTiers.tierOf(it) } ?: 0

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
        val query = QueryProductDetailsParams.newBuilder().setProductList((1..PlanTiers.MAX_TIER).map { tier ->
            QueryProductDetailsParams.Product.newBuilder().setProductId(PlanTiers.productId(tier)).setProductType(BillingClient.ProductType.SUBS).build()
        }).build()
        client.queryProductDetailsAsync(query) { result, details ->
            plans = if (result.responseCode != BillingClient.BillingResponseCode.OK) emptyMap()
                else details.productDetailsList.mapNotNull { product -> PlanTiers.tierOf(product.productId).takeIf { it > 0 }?.let { tier -> bestOffer(product, tier)?.let { tier to it } } }.toMap()
            status = if (plans.isEmpty()) "The subscription is not available yet. Check that the app was installed from Google Play." else ""
        }
        restore()
    }

    /** Re-reads active purchases (Restore purchase) and hands each to the app, which acknowledges and records it. */
    fun restore() {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val active = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.any { p -> PlanTiers.tierOf(p) > 0 } }
            ownedOnDevice = active.isNotEmpty()
            ownedPurchase = active.maxByOrNull { p -> p.products.maxOf { PlanTiers.tierOf(it) } }
            // Lowest first, so the highest tier is recorded last.
            active.sortedBy { p -> p.products.maxOf { PlanTiers.tierOf(it) } }.forEach(onPurchase)
        }
    }

    /** Eligible offers come first from Play; prefer the one with the cheapest first payment (the launch offer). */
    private fun bestOffer(product: ProductDetails, tier: Int): PlanOption? {
        val offers = product.subscriptionOfferDetails.orEmpty()
        val chosen = offers.minByOrNull { offer -> offer.pricingPhases.pricingPhaseList.firstOrNull()?.priceAmountMicros ?: Long.MAX_VALUE } ?: return null
        val phases = chosen.pricingPhases.pricingPhaseList
        val regular = phases.last()
        val intro = phases.takeIf { it.size > 1 }?.first()
        return PlanOption(product, chosen.offerToken, chosen.offerId, intro?.formattedPrice, intro?.billingCycleCount ?: 0, regular.formattedPrice, periodName(regular.billingPeriod), tier)
    }

    private fun periodName(iso: String) = when (iso) { "P1M" -> "month"; "P1Y" -> "year"; "P1W" -> "week"; "P3M" -> "3 months"; "P6M" -> "6 months"; else -> iso }

    /** Buys [tier]; when this Play account already has another tier, Google Play switches it (prorated). */
    fun purchase(activity: Activity, clinicId: String, tier: Int = 1) {
        val option = plans[tier] ?: run { status = "That plan is not available in Google Play yet."; return }
        val builder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(option.details).setOfferToken(option.offerToken).build()))
            // Ties the purchase to this clinic so another clinic's devices ignore it.
            .setObfuscatedAccountId(accountTag(clinicId))
        val current = ownedPurchase
        if (current != null && ownedTier != tier) {
            builder.setSubscriptionUpdateParams(BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                .setOldPurchaseToken(current.purchaseToken)
                // Upgrade: charge the difference now. Downgrade: switch at the next renewal.
                .setSubscriptionReplacementMode(if (tier > ownedTier) BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE
                    else BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED)
                .build())
        }
        val result = client.launchBillingFlow(activity, builder.build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) status = "Could not open Google Play checkout (${result.responseCode})"
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { purchase ->
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    ownedOnDevice = true
                    if (purchase.products.maxOf { PlanTiers.tierOf(it) } >= ownedTier) ownedPurchase = purchase
                    onPurchase(purchase)
                }
                else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) status = "Payment pending. Access starts when Google Play confirms it."
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> status = ""
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            else -> status = "Purchase failed: ${result.debugMessage.ifBlank { "code ${result.responseCode}" }}"
        }
    }

    /** There is no server, so the device acknowledges the purchase (Google refunds it if not acknowledged within 3 days). */
    fun acknowledgeLocally(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { }
    }

    fun manageUrl() = "https://play.google.com/store/account/subscriptions?sku=${ownedPurchase?.products?.firstOrNull() ?: PlanTiers.productId(1)}&package=${context.packageName}"

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
    // Price follows the clinic's number of patients: one tier per 1,000 patients.
    val tier = store.requiredTier
    val plan = billing.plans[tier]
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(HeroTop, AppBackground))).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        DentalAppLogo(Modifier.size(64.dp))
        Text("Dental Care Pro", style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text("One subscription for your whole clinic: every doctor and staff member. Patients use their app access free of charge.",
            style = MaterialTheme.typography.bodyMedium, color = InkMuted, textAlign = TextAlign.Center)
        Text("Your clinic: %,d patients · ${PlanTiers.rangeText(tier)} plan".format(store.activePatientCount),
            style = MaterialTheme.typography.titleSmall, color = Teal700, textAlign = TextAlign.Center)
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
                Text(PlanTiers.rangeText(tier), color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.labelLarge)
                if (plan == null) {
                    Text(PlanTiers.priceText(tier), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(billing.status.ifBlank { "Loading price…" }, color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall)
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
        PlanTable(billing, tier)
        if (canBuy) Button(
            onClick = { (context as? Activity)?.let { billing.purchase(it, store.subscriptionClinicId, tier) } },
            enabled = plan != null && !store.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp),
        ) { Text(if (plan?.introPrice != null) "Subscribe for ${plan.introPrice}" else "Subscribe") }
        else SurfaceCard(background = SurfaceTint) {
            Text("Your clinic's subscription is not active", style = MaterialTheme.typography.titleSmall, color = Ink)
            Text("Ask the clinic owner or administrator to subscribe. You'll get access automatically.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
        ButtonRow(listOf<@Composable (Modifier) -> Unit>(
            { m -> OutlinedButton(onClick = { billing.restore(); store.refreshSubscription() }, shape = RoundedCornerShape(14.dp), modifier = m) { ButtonLabel("Restore purchase") } },
            { m -> OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(billing.manageUrl()))) }, shape = RoundedCornerShape(14.dp), modifier = m) { ButtonLabel("Manage") } },
        ), minItemWidth = 140.dp)
        if (plan != null && billing.status.isNotBlank()) Text(billing.status, color = InkMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        if (store.message.isNotBlank()) Text(store.message, color = InkMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        TextButton(onClick = { if (store.session == "local") store.leaveLocalMode() else store.signOut() }) { Text("Sign out") }
    }
}

/** Prices per tier (1,000 patients each); the clinic's current tier is highlighted. */
@Composable
fun PlanTable(billing: BillingManager, highlight: Int) {
    SurfaceCard(padding = 12.dp) {
        Text("Price by number of patients", style = MaterialTheme.typography.titleSmall, color = Ink)
        val last = (highlight + 2).coerceAtMost(PlanTiers.MAX_TIER).coerceAtLeast(3)
        (1..last).forEach { tier ->
            val plan = billing.plans[tier]
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (tier == highlight) Teal50 else Color.Transparent).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(PlanTiers.rangeText(tier), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Ink,
                    fontWeight = if (tier == highlight) FontWeight.SemiBold else FontWeight.Normal)
                Text(plan?.let { (it.introPrice ?: it.regularPrice) + " / " + it.period } ?: "US\$${PlanTiers.OFFER_USD_PER_TIER * tier} / month",
                    style = MaterialTheme.typography.bodySmall, color = Teal700, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(plan?.regularPrice ?: "US\$${PlanTiers.REGULAR_USD_PER_TIER * tier}", style = MaterialTheme.typography.bodySmall, color = InkMuted,
                    textDecoration = TextDecoration.LineThrough)
            }
        }
        Text("Each further 1,000 patients adds US\$${PlanTiers.OFFER_USD_PER_TIER} a month (normally US\$${PlanTiers.REGULAR_USD_PER_TIER}). Prices in your currency come from Google Play.",
            style = MaterialTheme.typography.bodySmall, color = InkMuted)
    }
}

/** Settings card: current plan, patients used, upgrade, and Google Play subscription management. */
@Composable
fun SubscriptionCard(store: ClinicStore, billing: BillingManager) {
    val context = LocalContext.current
    val sub = store.subscription
    LaunchedEffect(Unit) { billing.connect() }
    SurfaceCard {
        Text("Subscription", style = MaterialTheme.typography.titleSmall, color = Ink)
        Text(when {
            store.isSuperAdmin -> "Super admin · free access, no subscription required."
            sub.optBoolean("complimentary") -> "Complimentary access (clinic owned by a super admin)."
            BuildConfig.DEBUG && !store.subscriptionActive -> "Debug build: subscription check bypassed for testing."
            store.subscriptionActive && sub.optLong("expiresAt") > 0 -> "Dental Care Pro · ${PlanTiers.rangeText(store.planTier)} · active until ${java.text.DateFormat.getDateInstance().format(java.util.Date(sub.optLong("expiresAt")))}" +
                if (sub.optBoolean("autoRenewing", true)) " (renews automatically)" else " (will not renew)"
            store.subscriptionActive -> "Dental Care Pro · active"
            else -> "Not subscribed"
        }, style = MaterialTheme.typography.bodySmall, color = InkMuted)
        store.patientLimit?.let { limit ->
            val used = store.activePatientCount
            LinearProgressIndicator(progress = { (used.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                color = if (used >= limit) Rose600 else Teal600, trackColor = SurfaceTint)
            Text("%,d of %,d patients on your plan".format(used, limit), style = MaterialTheme.typography.bodySmall, color = if (used >= limit) Rose600 else InkMuted)
        }
        val canBuy = store.currentRole in listOf("owner", "admin", "administrator")
        if (!store.freeAccess && store.subscriptionActive && canBuy) {
            val next = maxOf(store.requiredTier, store.planTier + 1).coerceAtMost(PlanTiers.MAX_TIER)
            if (next > store.planTier) Button(onClick = { (context as? Activity)?.let { billing.purchase(it, store.subscriptionClinicId, next) } },
                enabled = billing.plans[next] != null, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Upgrade: ${PlanTiers.rangeText(next).lowercase()} · " + (billing.plans[next]?.let { it.introPrice ?: it.regularPrice } ?: "US\$${PlanTiers.OFFER_USD_PER_TIER * next}") + " / month")
            }
            if (billing.ownedPurchase == null) Text("Upgrade from the phone that bought the subscription (same Google account).", style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
        if (!store.freeAccess) PlanTable(billing, if (store.subscriptionActive) store.planTier else store.requiredTier)
        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(billing.manageUrl()))) }, shape = RoundedCornerShape(14.dp)) { Text("Manage in Google Play") }
    }
}

package com.spala.dental

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Currency
import java.util.Locale

/*
 * Clinic branding. The admin's clinic name, logo, address, phone, Google Maps location and colour
 * (stored in the clinic settings, shared with the web app and the patient app) replace the
 * Dental Care branding: launch screen, toolbar, sign-in, patient app, PDF reports. The last brand
 * is cached on the device so the next launch opens with the clinic's own logo straight away.
 */
object ClinicBrand {
    val colors = listOf("#0F766E", "#0E7490", "#1D4ED8", "#4338CA", "#7C3AED", "#BE185D", "#B91C1C", "#C2410C", "#A16207", "#15803D", "#334155")
    val currencies = listOf("BDT", "INR", "PKR", "NPR", "LKR", "USD", "EUR", "GBP", "JPY", "KRW", "CNY", "SAR", "AED", "QAR", "KWD", "MYR", "SGD",
        "IDR", "THB", "PHP", "VND", "AUD", "CAD", "BRL", "MXN", "ARS", "COP", "ZAR", "NGN", "EGP", "TRY", "RUB", "CHF")

    var name by mutableStateOf(""); private set
    var address by mutableStateOf(""); private set
    var phone by mutableStateOf(""); private set
    var mapUrl by mutableStateOf(""); private set
    var color by mutableStateOf<Color?>(null); private set
    var logo by mutableStateOf<ImageBitmap?>(null); private set
    private var logoData = ""
    private var prefs: android.content.SharedPreferences? = null

    val appName: String get() = name.ifBlank { "Dental Care" }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("clinic_brand", Context.MODE_PRIVATE)
        runCatching { apply(JSONObject(prefs?.getString("brand", "{}") ?: "{}"), persist = false) }
    }

    /** Applies the brand in a clinic settings / patient-app clinic object and caches it. */
    fun apply(config: JSONObject, persist: Boolean = true) {
        name = config.optString("name").takeIf { it != "Dental Care" }.orEmpty()
        address = config.optString("address"); phone = config.optString("clinicPhone"); mapUrl = config.optString("mapUrl")
        color = colorOf(config.optString("brandColor"))?.let { Color(it) }
        val data = config.optString("logo")
        if (data != logoData) { logoData = data; logo = decodeLogo(data)?.asImageBitmap() }
        if (persist) prefs?.edit()?.putString("brand", JSONObject().put("name", config.optString("name")).put("address", address).put("clinicPhone", phone)
            .put("mapUrl", mapUrl).put("brandColor", config.optString("brandColor")).put("logo", data).toString())?.apply()
    }

    fun colorOf(hex: String?): Int? = hex?.trim()?.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }?.let { android.graphics.Color.parseColor(it) }

    fun decodeLogo(data: String?): Bitmap? {
        if (data.isNullOrBlank() || !data.startsWith("data:image")) return null
        return runCatching { val bytes = Base64.decode(data.substringAfter(','), Base64.DEFAULT); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }

    /** Reads a picked image and returns a small PNG data URL (max 256 px) for the clinic settings. */
    fun encodeLogo(context: Context, uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose an image file (PNG, JPG or WebP)" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
        val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("Could not read that image")
        for (max in listOf(256, 192, 128, 96)) {
            val scale = minOf(1f, max.toFloat() / maxOf(source.width, source.height))
            val bitmap = Bitmap.createScaledBitmap(source, maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), true)
            val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            val data = "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (data.length < 120_000) return data
        }
        error("That logo is too detailed. Use a simpler image.")
    }

    /** Google Maps search text for a saved map link, "lat,lng" or the address. */
    fun mapQuery(config: JSONObject): String {
        val url = config.optString("mapUrl").trim()
        if (url.isNotEmpty()) {
            Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)").find(url)?.let { return "${it.groupValues[1]},${it.groupValues[2]}" }
            Regex("^(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)$").find(url)?.let { return "${it.groupValues[1]},${it.groupValues[2]}" }
            Regex("[?&](?:q|query)=([^&]+)").find(url)?.let { return Uri.decode(it.groupValues[1].replace('+', ' ')) }
            if (!url.startsWith("http", ignoreCase = true)) return url
        }
        return listOf(config.optString("name"), config.optString("address")).filter { it.isNotBlank() }.joinToString(", ")
    }
    fun mapLink(config: JSONObject): String {
        val url = config.optString("mapUrl").trim()
        return if (url.startsWith("http", ignoreCase = true)) url else "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(mapQuery(config))
    }
    fun openMap(context: Context, config: JSONObject) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapLink(config))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, "No maps app found", Toast.LENGTH_SHORT).show() }
    }

    fun currencyName(code: String): String = runCatching { Currency.getInstance(code).getDisplayName(Locale.forLanguageTag(if (I18n.lang == "pt") "pt-BR" else if (I18n.lang == "zh") "zh-CN" else I18n.lang)) }.getOrDefault(code)
}

/** Material colours follow the clinic's brand colour (buttons, switches, tabs, progress). */
@Composable
fun BrandTheme(content: @Composable () -> Unit) {
    val c = ClinicBrand.color
    if (c == null) { content(); return }
    val base = MaterialTheme.colorScheme
    val dark = base.background.red + base.background.green + base.background.blue < 1.2f
    val primary = if (dark) lerp(c, Color.White, 0.35f) else c
    MaterialTheme(
        colorScheme = base.copy(
            primary = primary, onPrimary = if (dark) Color(0xFF08110F) else Color.White,
            primaryContainer = if (dark) lerp(c, Color.Black, 0.55f) else lerp(c, Color.White, 0.84f),
            onPrimaryContainer = if (dark) lerp(c, Color.White, 0.8f) else lerp(c, Color.Black, 0.45f),
            secondary = primary, secondaryContainer = if (dark) lerp(c, Color.Black, 0.6f) else lerp(c, Color.White, 0.88f),
            surfaceTint = primary,
        ),
        typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content,
    )
}

/** The clinic logo when one is set, otherwise the Dental Care logo. */
@Composable
fun ClinicLogo(modifier: Modifier = Modifier) {
    val bitmap = ClinicBrand.logo
    if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.clip(RoundedCornerShape(12.dp)))
    else Image(androidx.compose.ui.res.painterResource(R.drawable.ic_dental_logo), contentDescription = null, modifier = modifier)
}

/** Launch screen with the clinic's own logo and name. */
@Composable
fun BrandSplash() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ClinicLogo(Modifier.size(112.dp))
            androidx.compose.material3.Text(ClinicBrand.appName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
        }
    }
}

/** Settings › Clinic branding (admins): logo, name, address, phone, map location, colour. */
@Composable
fun BrandingPanel(config: JSONObject, onSave: (JSONObject) -> Unit) {
    val context = LocalContext.current
    var name by remember(config) { mutableStateOf(config.optString("name", "Dental Care")) }
    var address by remember(config) { mutableStateOf(config.optString("address")) }
    var phone by remember(config) { mutableStateOf(config.optString("clinicPhone")) }
    var mapUrl by remember(config) { mutableStateOf(config.optString("mapUrl")) }
    var logo by remember(config) { mutableStateOf(config.optString("logo")) }
    var color by remember(config) { mutableStateOf(config.optString("brandColor")) }
    var error by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching { ClinicBrand.encodeLogo(context, uri) }.onSuccess { logo = it; error = "" }.onFailure { error = it.localizedMessage ?: "Could not read that image" }
    }
    val preview = remember(logo) { ClinicBrand.decodeLogo(logo)?.asImageBitmap() }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Clinic branding", style = MaterialTheme.typography.titleMedium)
            Text("Your clinic's own app for staff and patients: logo at launch, in the menu, the patient app and on every PDF.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(76.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                    if (preview != null) Image(preview, null, Modifier.size(68.dp), contentScale = ContentScale.Fit)
                    else Image(androidx.compose.ui.res.painterResource(R.drawable.ic_dental_logo), null, Modifier.size(52.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { picker.launch("image/*") }) { Text("Choose logo") }
                    if (logo.isNotBlank()) TextButton(onClick = { logo = "" }) { Text("Remove logo") }
                }
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Clinic name") }, singleLine = true)
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Address") })
            OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), label = { Text("Phone") }, singleLine = true)
            OutlinedTextField(mapUrl, { mapUrl = it }, Modifier.fillMaxWidth(), label = { Text("Google Maps location") }, singleLine = true,
                supportingText = { Text("Google Maps › Share › Copy link, or lat,lng") })
            Text("Brand color", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().then(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClinicBrand.colors.take(6).forEach { hex -> Swatch(hex, color.equals(hex, true)) { color = hex } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ClinicBrand.colors.drop(6).forEach { hex -> Swatch(hex, color.equals(hex, true)) { color = hex } }
                TextButton(onClick = { color = "" }) { Text("Default") }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val draft = JSONObject().put("name", name).put("address", address).put("mapUrl", mapUrl)
            ButtonRow(buildList<@Composable (Modifier) -> Unit> {
                add { m -> Button(modifier = m, onClick = {
                    runCatching {
                        val next = JSONObject(config.toString()).put("name", name.trim().ifBlank { "Dental Care" }).put("address", address.trim())
                            .put("clinicPhone", phone.trim()).put("mapUrl", mapUrl.trim()).put("brandColor", color)
                        if (logo.isBlank()) next.remove("logo") else next.put("logo", logo)
                        ClinicalRules.validateConfig(next); next
                    }.onSuccess { onSave(it); ClinicBrand.apply(it) }.onFailure { error = it.localizedMessage ?: "Review the values" }
                }) { ButtonLabel("Save branding") } }
                if (ClinicBrand.mapQuery(draft).isNotBlank()) add { m -> OutlinedButton(modifier = m, onClick = { ClinicBrand.openMap(context, draft) }) { ButtonLabel("Open in Google Maps") } }
            }, minItemWidth = 150.dp)
        }
    }
}

@Composable
private fun Swatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(hex)))
        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable(onClick = onClick))
}

/** Clinic currency (admins). New invoices use it; existing invoices keep their own currency. */
@Composable
fun CurrencyPicker(config: JSONObject, onSave: (JSONObject) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = config.optString("currency", "BDT")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { open = true }) { androidx.compose.material3.Text(I18n.tr("Currency") + ": " + current + " · " + ClinicBrand.currencyName(current)) }
        Text("Existing invoices keep their currency", style = MaterialTheme.typography.bodySmall)
    }
    if (open) ChoiceDialog("Currency", (listOf(current) + ClinicBrand.currencies).distinct().map { it to "$it · ${ClinicBrand.currencyName(it)}" }, current,
        translateLabels = false, onDismiss = { open = false }) { code ->
        open = false
        if (code != current) runCatching { JSONObject(config.toString()).put("currency", code).also { ClinicalRules.validateConfig(it) } }.onSuccess(onSave)
    }
}

/** Patient app: where the clinic is, with a call button and directions. */
@Composable
fun FindUsCard(clinic: JSONObject) {
    val context = LocalContext.current
    val hasMap = ClinicBrand.mapQuery(clinic).isNotBlank() && (clinic.optString("address").isNotBlank() || clinic.optString("mapUrl").isNotBlank())
    val phone = clinic.optString("clinicPhone")
    if (!hasMap && phone.isBlank()) return
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Find us", style = MaterialTheme.typography.titleMedium)
            androidx.compose.material3.Text(clinic.optString("name"), style = MaterialTheme.typography.titleSmall)
            if (clinic.optString("address").isNotBlank()) androidx.compose.material3.Text(clinic.optString("address"), style = MaterialTheme.typography.bodyMedium)
            ButtonRow(buildList<@Composable (Modifier) -> Unit> {
                if (hasMap) add { m -> Button(modifier = m, onClick = { ClinicBrand.openMap(context, clinic) }) { ButtonLabel("Directions") } }
                if (phone.isNotBlank()) add { m -> OutlinedButton(modifier = m, onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' }))) }
                }) { androidx.compose.material3.Text("☎  $phone", maxLines = 1) } }
            }, minItemWidth = 140.dp)
        }
    }
}

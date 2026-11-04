package com.spala.dental

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/*
 * Interface languages. The English text written in the screens is the key; assets/i18n/<code>.json
 * (the same files as the web app, Web/i18n) maps it to the chosen language. The [Text] below
 * shadows Material's Text for this package, so every plain-string label is translated without
 * changing the screens. Patient records, names and notes are never translated.
 */
object I18n {
    data class Language(val code: String, val name: String)
    val languages = listOf(
        Language("en", "English"), Language("bn", "বাংলা"), Language("hi", "हिन्दी"), Language("ar", "العربية"),
        Language("zh", "中文 (简体)"), Language("es", "Español"), Language("fr", "Français"), Language("pt", "Português"),
        Language("de", "Deutsch"), Language("ru", "Русский"), Language("ja", "日本語"), Language("ko", "한국어"),
        Language("id", "Bahasa Indonesia"), Language("tr", "Türkçe"), Language("it", "Italiano"), Language("vi", "Tiếng Việt"),
        Language("th", "ไทย"),
    )
    var lang by mutableStateOf("en"); private set
    private var dict by mutableStateOf<Map<String, String>>(emptyMap())
    val rtl: Boolean get() = lang == "ar"
    val languageName: String get() = languages.firstOrNull { it.code == lang }?.name ?: "English"

    fun init(context: Context) {
        val saved = context.getSharedPreferences("dental_prefs", Context.MODE_PRIVATE).getString("language", "en") ?: "en"
        load(context, saved)
    }
    fun set(context: Context, code: String) {
        context.getSharedPreferences("dental_prefs", Context.MODE_PRIVATE).edit().putString("language", code).apply()
        load(context, code)
    }
    private fun load(context: Context, code: String) {
        val chosen = if (languages.any { it.code == code }) code else "en"
        dict = if (chosen == "en") emptyMap() else runCatching {
            val json = JSONObject(context.assets.open("i18n/$chosen.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
        lang = chosen
    }
    /** The translation of an English interface string (unchanged when there is none). */
    fun tr(text: String): String {
        if (lang == "en" || text.isEmpty()) return text
        return dict[text] ?: dict[text.trim()]?.let { text.replace(text.trim(), it) } ?: text
    }
}

/** Material Text with the label translated into the chosen language. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text = I18n.tr(text), modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
    fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
    lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines,
    onTextLayout = onTextLayout, style = style,
)

/** A button showing the current language; opens the list of languages. */
@Composable
fun LanguagePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) { androidx.compose.material3.Text("🌐  " + I18n.languageName) }
    if (open) ChoiceDialog("Language", I18n.languages.map { it.code to it.name }, I18n.lang, translateLabels = false, onDismiss = { open = false }) {
        I18n.set(context, it); open = false
    }
}

/** A simple single-choice list in a dialog (languages, currencies). */
@Composable
fun ChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String, translateLabels: Boolean = true, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            options.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = value == selected, onClick = { onPick(value) })
                    if (translateLabels) Text(label, style = MaterialTheme.typography.bodyLarge)
                    else androidx.compose.material3.Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

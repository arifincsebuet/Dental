package com.spala.dental

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val GuideTeal = Color(0xFF0F766E)
private val GuideSlate = Color(0xFF1E293B)
private val GuideMint = Color(0xFFF0FDFA)

private data class GuideArticle(
    val id: String,
    val topic: String,
    val title: String,
    val summary: String,
    val steps: List<String>,
    val reminder: String? = null
) {
    fun matches(query: String): Boolean = query.isBlank() ||
        (listOf(topic, title, summary) + steps + listOfNotNull(reminder)).any { it.contains(query, ignoreCase = true) }
}

/** Bundled operational help. It can be opened before an account or clinic is configured. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowToUseScreen(onBack: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("All topics") }
    var expanded by rememberSaveable { mutableStateOf("start") }
    val articles = remember(search, topic) {
        GuideArticles.filter { (topic == "All topics" || it.topic == topic) && it.matches(search.trim()) }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = GuideMint,
        topBar = {
            TopAppBar(
                title = { Text("How to use", color = GuideSlate, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back to settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Make each visit easier", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = GuideSlate)
                Text("Find the steps for patients, dental charts, appointments, and clinic records.",
                    modifier = Modifier.padding(top = 6.dp), color = GuideSlate.copy(alpha = .75f))
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search the guide") },
                    placeholder = { Text("Try 3D chart, refund, or sync") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        if (search.isNotBlank()) TextButton(onClick = { search = "" }) { Text("Clear") }
                    }
                )
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("All topics") + GuideArticles.map { it.topic }.distinct()).forEach { label ->
                        FilterChip(selected = topic == label, onClick = { topic = label }, label = { Text(label) })
                    }
                }
            }
            item {
                Text("${articles.size} ${if (articles.size == 1) "guide" else "guides"} · tap a card to read the steps",
                    style = MaterialTheme.typography.bodySmall, color = GuideSlate.copy(alpha = .7f))
            }
            if (articles.isEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No matching guide", fontWeight = FontWeight.SemiBold)
                        Text("Try a shorter search or choose All topics.")
                        TextButton(onClick = { search = ""; topic = "All topics" }) { Text("Show all guides") }
                    }
                }
            }
            items(articles, key = { it.id }) { article ->
                val isExpanded = expanded == article.id
                Card(
                    onClick = { expanded = if (isExpanded) "" else article.id },
                    modifier = Modifier.fillMaxWidth().semantics {
                        stateDescription = if (isExpanded) "Expanded" else "Collapsed"
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(article.topic.uppercase(), color = GuideTeal, style = MaterialTheme.typography.labelSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(article.title, modifier = Modifier.weight(1f).semantics { heading() },
                                color = GuideSlate, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(if (isExpanded) "Hide steps" else "Show steps", color = GuideTeal,
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp))
                        }
                        Text(article.summary, color = GuideSlate.copy(alpha = .8f), style = MaterialTheme.typography.bodyMedium)
                        if (isExpanded) {
                            HorizontalDivider(color = GuideMint)
                            article.steps.forEachIndexed { index, instruction ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Surface(color = GuideMint, shape = RoundedCornerShape(8.dp)) {
                                        Text("${index + 1}", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            color = GuideTeal, fontWeight = FontWeight.Bold)
                                    }
                                    Text(instruction, modifier = Modifier.weight(1f).padding(top = 4.dp),
                                        color = GuideSlate, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            article.reminder?.let { note ->
                                Surface(color = GuideMint, shape = RoundedCornerShape(12.dp)) {
                                    Text(note, modifier = Modifier.padding(14.dp), color = GuideSlate,
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

private val GuideArticles = listOf(
    GuideArticle(
        "start", "Getting started", "Your first patient visit",
        "A short route from registration to a recorded care plan.",
        listOf(
            "Open Patients and search the name or phone number before creating another profile.",
            "Choose Register patient, enter the patient's details, and save. Open the profile and confirm the patient identity and medical alerts.",
            "Use Encounters to record the complaint, examination, diagnosis, and plan. Use Chart to select a tooth and record its assessment.",
            "Review proposed care in Treatments. Use Schedule for the next appointment and Billing for invoices and payments.",
            "Read the save message. Device saved · sync pending means the change is on this device. Use Sync when the clinic's cloud workspace is ready."
        ),
        "Available controls depend on your clinic role. Ask the clinic owner if an expected clinical or billing action is unavailable."
    ),
    GuideArticle(
        "patients", "Patients", "Find, update, or archive a patient",
        "Keep the profile and clinical story with the correct person.",
        listOf(
            "Open Patients and search by name or phone. Select the patient to open Overview.",
            "Use Edit profile to correct demographics and medical alerts where your role allows it. Tap Date of birth to pick it from the calendar.",
            "Use Update clinical story for the patient's clinical narrative. Review the selected name before saving a change.",
            "Archive patient requires a reason and keeps the record. Use Include archived patients in the patient list to find it again, then Restore patient if appropriate."
        ),
        "If registration reports a duplicate, find the existing patient and review their identity before making another record."
    ),
    GuideArticle(
        "chart", "Dental charts", "Assess a tooth in the 2D chart",
        "Record assessment, presence, findings, and restorations as separate facts.",
        listOf(
            "Open a patient, choose Chart, and select permanent, primary, or mixed dentition. Mixed includes both primary and permanent slots.",
            "Tap the numbered tooth. Choose its assessment and presence, then record a replacement if applicable.",
            "Choose Whole tooth or a surface before adding a finding. Select a specific surface when recording a restoration.",
            "Enter an optional planned procedure and a clinical note, then choose Save assessment.",
            "Review existing facts before adding more. Resolve findings at selected scope only when the clinical finding has actually been resolved."
        ),
        "An unrecorded tooth or surface is unassessed. It is never automatically healthy. Record missing, extracted, exfoliated, or congenitally absent teeth explicitly."
    ),
    GuideArticle(
        "numbering", "Dental charts", "Understand tooth numbers and surfaces",
        "The chart uses FDI numbers and consistent surface letters.",
        listOf(
            "Permanent slots use 11–18, 21–28, 31–38, and 41–48. Primary slots use 51–55, 61–65, 71–75, and 81–85.",
            "Primary 51 and permanent 11 are separate slots. Choose mixed dentition when both sets need to be represented. Recorded slots are retained when reviewing history.",
            "M means mesial; D means distal; F means facial (labial or buccal); L means lingual or palatal.",
            "I is the incisal surface for incisors and canines. O is the occlusal surface for premolars and molars.",
            "Read the chart legend and the F, R, and P indicators: recorded finding codes, restoration codes, and open treatment states."
        ),
        "Assessment of a whole tooth does not automatically mean that every surface was assessed."
    ),
    GuideArticle(
        "three-d", "Dental charts", "Use the interactive 3D chart",
        "Inspect the teeth, then return to the native editor to record care.",
        listOf(
            "In Chart, choose Open interactive 3D chart. Drag with one finger to rotate the dentition.",
            "Use two fingers to pan and pinch to zoom. The + and − controls also change zoom; Reset restores the view.",
            "Use Upper or Lower to show or hide an arch. Labels and Roots control the corresponding overlays.",
            "Tap a tooth to see its recorded state. Select a surface if needed, then choose Assess tooth to open the native assessment editor.",
            "Use Focus to bring the selected tooth into view, or Back to return without making an assessment."
        ),
        "Historical and patient explanation charts are read-only. The native 2D chart remains available for tooth selection and editing."
    ),
    GuideArticle(
        "encounters", "Clinical care", "Write and finalize an encounter",
        "Keep an examination note and preserve later corrections.",
        listOf(
            "Open Encounters and choose New encounter. Record the clinician, complaint, examination, diagnosis, and plan.",
            "Use Edit draft while the note is still a draft. Review the note and the patient identity before choosing Finalize.",
            "To correct a finalized note, choose Amend with reason. Enter both the reason and the correction.",
            "Review amendments with the original encounter. History records the change."
        ),
        "A device save and cloud confirmation are different states. Synchronize changes before relying on a shared clinic record."
    ),
    GuideArticle(
        "treatments", "Clinical care", "Plan and complete treatment",
        "Proposed care and performed care remain distinguishable.",
        listOf(
            "Select a tooth in Chart and enter a Planned procedure to create a treatment item.",
            "Open Treatments and review the tooth, surface, procedure, notes, and current status.",
            "Choose an available next state, such as Accepted, In progress, Deferred, Declined, Referred, or Cancelled. Record the decision reason when requested.",
            "For Completed care, record the clinician and actual outcome. A supported restoration code can be recorded for a specific surface when appropriate.",
            "Review the separate Performed procedures section. Use Amend with a reason to correct a performed record."
        ),
        "Completing a procedure does not automatically resolve every finding. Reassess the tooth and resolve the relevant finding explicitly."
    ),
    GuideArticle(
        "appointments", "Appointments", "Book or reschedule an appointment",
        "Use the clinic time zone, duration, clinician, and room to plan the visit.",
        listOf(
            "Open Schedule. Choose Day, Week, Month, or All and use the calendar date, Previous, Today, or Next controls.",
            "Choose Book appointment and select the patient. Enter the clinician, date and time, duration, room if used, and reason or service.",
            "Tap Date & time to pick the day on the calendar, then the time on the clock (clinic time zone). Review any clinician or room conflict, including the clinic buffer, before saving.",
            "Choose Reschedule / edit on an active appointment to change its details.",
            "Use the available status actions to confirm, check in, start, complete, cancel, or record a no-show. Open patient takes you back to the associated record."
        ),
        "A locally saved appointment may still conflict with a change from another device. Check the synchronization result before treating a shared reservation as confirmed."
    ),
    GuideArticle(
        "billing", "Billing", "Create an invoice and receive payment",
        "Record line items and distinguish billed amounts from money received.",
        listOf(
            "Open the patient's Billing tab and choose Create invoice.",
            "Select an available service or enter a line description, unit price, and quantity. Choose Add line for each item.",
            "Review the discount, tax, currency, and lines, then choose Issue invoice. Issued prices are retained with that invoice.",
            "Choose Receive payment on an unpaid invoice. Enter the received amount, payment method, and optional reference. Partial payments leave an outstanding balance.",
            "Use Share receipt for a payment receipt, or Share invoice for an invoice summary."
        ),
        "Recording a payment records the clinic transaction. It does not charge a payment card or move money through a payment provider."
    ),
    GuideArticle(
        "refunds", "Billing", "Refund a receipt or void an invoice",
        "Corrections retain their reasons and original records.",
        listOf(
            "Find the original payment receipt in Billing and choose Refund.",
            "Enter the refund amount, method, and required reason. The amount must be within the unrefunded part of that receipt.",
            "Review the updated received, refunded, and outstanding totals.",
            "To cancel an issued invoice, refund its receipts first if needed, then choose Void and enter the required reason."
        ),
        "A voided invoice keeps its history. Use a corrected invoice rather than rewriting issued prices."
    ),
    GuideArticle(
        "reports", "Reports", "Review and share practice reports",
        "Compare activity and finances for the selected reporting period.",
        listOf(
            "Open Reports and choose Day, Week, Month, Custom, or All.",
            "For Custom, pick the first and last dates on the calendar. The last date is included.",
            "Read billed amounts, payments received, refunds, net cash, and outstanding balances separately. Currency totals are shown separately.",
            "Review appointment states, clinician workload, and treatment activity below the financial totals.",
            "Choose Share financial report CSV to share the displayed financial report."
        ),
        "Reports use records available on this device. Restore or synchronize the clinic records as appropriate before preparing a shared report."
    ),
    GuideArticle(
        "images", "Records and sync", "Attach and view a clinical image",
        "Images retain their decoded pixel detail and must fit the size limit.",
        listOf(
            "Open the patient Overview and choose Attach image. Select the image using the device picker.",
            "The app prepares a lossless PNG image. The resulting file must be within 512 KB; images above 20 megapixels are also rejected.",
            "If the lossless result is too large, choose a smaller source image. The app does not silently lower image quality or resize it.",
            "After upload, synchronize the patient record to save its attachment reference with the shared record.",
            "Use View clinical image on an attachment to open it. Image upload and viewing require the clinic's cloud access to be available."
        ),
        "Confirm the selected patient before launching the image picker."
    ),
    GuideArticle(
        "sync", "Records and sync", "Save, synchronize, or resolve a conflict",
        "Understand what is on this device and what the clinic has confirmed.",
        listOf(
            "Saved on this device or Device saved · sync pending means the edit is stored locally. Cloud synchronized means the cloud save has been acknowledged.",
            "Choose Sync in the top bar, or Synchronize changes in Settings, to submit local changes to the configured clinic workspace.",
            "For a temporary connection failure, keep the record and retry Sync. The app can retry an already submitted change without creating it twice.",
            "If another device changed the record, use Review conflict · retain draft and load server version. Your local draft is kept for comparison.",
            "Find Conflict drafts in Settings and export the retained draft. Review the server record and reapply the changes you intend through the usual editors. Remove the exported draft only after preserving the copy you need."
        ),
        "A pending submission may temporarily prevent editing that patient. Read the failure message and resolve it before making another submission."
    ),
    GuideArticle(
        "history", "Records and sync", "Review history and explain care",
        "Compare previous observations and present the care plan to the patient.",
        listOf(
            "Open a patient's History tab to review the clinical timeline and available server audit entries.",
            "Use Compare chart on a change to review the earlier and current chart states.",
            "Use View earlier chart in 3D to inspect the earlier state in a read-only view.",
            "For a discussion with the patient, open Chart and choose Patient explanation view. Proposed care and completed care are shown separately.",
            "Return to the normal chart and clinical editors when you need to make a change."
        ),
        "Explanation mode is a clinician-operated view. Review the selected record before showing it to the patient."
    ),
    GuideArticle(
        "export", "Records and sync", "Export, import, and restore records",
        "Keep an approved copy and understand the difference between import and restore.",
        listOf(
            "In patient Overview, choose Export care summary PDF for a readable summary, or Export JSON for a structured patient copy. Choose its destination in the device picker.",
            "In Settings, Import patient JSON as a new record creates a separate imported patient. Review their identity and clinical information; private image attachments are not transferred to the new record.",
            "Restore cloud records loads the shared records into this device. Synchronize or resolve local changes first.",
            "Cloud restore is different from Restore patient, which brings an archived patient back into the active list.",
            "Before uninstalling or changing devices, keep the exports and synchronized records required by the clinic."
        ),
        "Exported files may contain sensitive patient information. Choose an appropriate destination and recipient."
    ),
    GuideArticle(
        "team", "Settings", "Configure the clinic, staff, and services",
        "Owners and administrators prepare the workspace used by day-to-day care.",
        listOf(
            "Open Settings and Clinic management. In Clinic, review the clinic name, time zone, currency, tax, and appointment buffer.",
            "In Staff, add staff names, contact and professional details, roles, and availability. Use Activate or Deactivate when staff status changes.",
            "In Services, enter procedure codes, descriptions, default fees, and duration. Changes affect future selections; issued invoice prices remain retained.",
            "Roles manages cloud account membership. Staff directory entries and account memberships are separate.",
            "Synchronize the configuration when the clinic workspace is ready so other authorized devices can use it."
        ),
        "If a setting is unavailable, ask the clinic owner or administrator to review your role."
    ),
    GuideArticle(
        "reminders", "Settings", "Enable appointment reminders",
        "Consent, reminder timing, quiet hours, and device permission work together.",
        listOf(
            "In Settings, open Clinic management → Reminders. Enable clinic reminders and review their lead time and quiet hours.",
            "For an appointment, enable Schedule appointment reminder and record patient consent. Review the appointment's lead hours before saving.",
            "Choose Allow appointment notifications in Settings and permit notifications when Android asks.",
            "For shared cloud reminders on this device, use Enable cloud reminders on this device after cloud setup is complete. This device then uses cloud delivery instead of local reminder delivery.",
            "Use Disable cloud reminders on this device to stop that delivery path. Your Schedule remains available to review appointments directly."
        ),
        "Notifications contain a general clinic reminder. They are staff reminders, not direct SMS or email messages to patients."
    ),
    GuideArticle(
        "account", "Settings", "Use an account or switch workspaces",
        "The app opens directly while Google sign-in is disabled.",
        listOf(
            "Start with records on this device. Google sign-in is currently disabled for this release.",
            "When the clinic's cloud workspace is configured, use Sync and Restore cloud records as directed by the clinic owner.",
            "To change clinic workspace, preserve your exports, synchronize or resolve changes, and choose Close synchronized device workspace. This removes synchronized local records while keeping cloud records.",
            "Use Choose cloud workspace to enter the workspace supplied by the clinic owner, then restore its records. Access also requires the appropriate membership.",
            "When Google sign-in is enabled in a future release, Continue with Google will become available in Settings. Preserve your existing records before changing accounts."
        ),
        "Closing a device workspace, signing out, archiving a patient, and deleting an exported file are different actions. Read the confirmation before proceeding."
    )
)

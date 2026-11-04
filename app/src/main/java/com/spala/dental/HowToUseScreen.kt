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
    val reminder: String? = null,
    /** "staff" (clinic app) or "patient" (patient app). */
    val audience: String = "staff",
) {
    fun matches(query: String): Boolean = query.isBlank() ||
        (listOf(topic, title, summary) + steps + listOfNotNull(reminder)).any { it.contains(query, ignoreCase = true) }
}

/**
 * Bundled step-by-step help (Settings › How to use Dental Care, the drawer, and the sign-in screen).
 * [audience] "staff" shows the clinic guides, "patient" the patient app guides, "all" both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowToUseScreen(onBack: () -> Unit, audience: String = "staff", backLabel: String = "Back") {
    var search by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("All topics") }
    var expanded by rememberSaveable { mutableStateOf(if (audience == "patient") "patient-signin" else "start") }
    val forAudience = remember(audience) { GuideArticles.filter { audience == "all" || it.audience == audience } }
    val articles = remember(search, topic, forAudience) {
        forAudience.filter { (topic == "All topics" || it.topic == topic) && it.matches(search.trim()) }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = GuideMint,
        topBar = {
            TopAppBar(
                title = { Text("How to use", color = GuideSlate, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text(backLabel) } },
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
                Text(if (audience == "patient") "Your dental care, in your pocket" else "Make each visit easier", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = GuideSlate)
                Text(if (audience == "patient") "How to sign in, see your teeth in 3D, read your treatment history and book appointments."
                    else "Step-by-step guides for every part of the app: 3D teeth, treatment, appointments, team and roles, billing and the patient app.",
                    modifier = Modifier.padding(top = 6.dp), color = GuideSlate.copy(alpha = .75f))
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search the guide") },
                    placeholder = { Text(if (audience == "patient") "Try book, password, or reminder" else "Try 3D, slots, roles, refund, or sync") },
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
                    (listOf("All topics") + forAudience.map { it.topic }.distinct()).forEach { label ->
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
        "start", "Getting started", "Sign in and set up your clinic",
        "The first Google account to sign in creates the clinic and becomes its owner.",
        listOf(
            "On the sign-in screen choose Clinic team, then Continue with Google.",
            "The first account to sign in creates a new clinic and becomes its owner (all access). Staff you add later join this clinic automatically.",
            "Open the ☰ menu › Clinic settings › Clinic management to set the clinic name, time zone, currency, tax and appointment buffer.",
            "Open ☰ › Team & roles and add your dentists and staff with their Google email (see the Team & roles guide).",
            "Choose a subscription in Settings, or continue if your account is free (super admin) or the clinic is complimentary.",
            "Use the bottom bar (Home, Patients, Schedule, Reports, Settings) or the ☰ menu to move around. The red badge on ☰ counts appointments in the next 7 days."
        ),
        "Without a Firebase project (offline build) choose Use offline on this device; records then stay only on this phone."
    ),
    GuideArticle(
        "menu", "Getting started", "Find your way: menu, Sync and messages",
        "The ☰ menu holds everything important; Sync shares your work with the clinic.",
        listOf(
            "Tap ☰ at the top left. Clinic: Home, Patients, Appointments, Reports. Quick actions: Register patient, Book appointment, Sync now. Administration: Team & roles, Clinic settings, Subscription. Help: How to use, Settings, Sign out.",
            "The Sync button at the top right sends your saved changes to the clinic and fetches others' changes and patient app bookings. Sync now in the menu shows how many changes are waiting.",
            "Saving a record stores it on this device first (encrypted). Team, slot and settings changes, and appointments you book, are shared right away.",
            "Green messages under the top bar confirm what happened; tap Dismiss to hide them. Red or amber text explains a problem and what to do."
        ),
        "If something is missing from the menu, your role may not include it. Ask your admin to check Team & roles."
    ),
    GuideArticle(
        "patients", "Patients", "Register, find, edit or archive a patient",
        "Keep one record per person with correct identity and medical alerts.",
        listOf(
            "Open Patients (or ☰ › Register patient). Search by name, phone or ID first so you don't create a duplicate.",
            "Choose Register patient. Enter the full name, phone, date of birth (tap to pick from the calendar) and medical alerts, then Save.",
            "Open the patient. The 3D teeth model is shown first, then the tabs: Overview, Chart, Encounters, Treatments, Billing, History.",
            "On Overview use Edit profile to correct details, Update next to Clinical story for the narrative, and the Appointment reminders switch for consent.",
            "Archive patient needs a reason and keeps the record. Turn on Include archived in Patients to find it, then choose Restore patient."
        ),
        "Registering and editing patients needs the \"Register & edit patients\" access item."
    ),
    GuideArticle(
        "three-d", "3D teeth", "Read the 3D teeth model on a patient's page",
        "Every patient page opens with an interactive 3D model coloured by their full treatment history.",
        listOf(
            "Open a patient. The page starts at the top with the 3D model; it loads, swings into view and then turns slowly on its own.",
            "Drag to rotate, pinch to zoom, double-tap to reset. Touching the model pauses the turning; it resumes 4 seconds after you let go and stays still while a tooth is selected.",
            "Colours: blue = treated, teal + = planned care, amber = finding, × = recorded loss, grey = not assessed. The counters under the model show Treated, Planned, Findings and Missing.",
            "Tap a tooth to select it (you hear a soft click; tapping it again deselects it). Its tooth number appears and its history opens in Treatment history below.",
            "Tooth numbers follow Settings › Tooth numbering (Palmer, FDI or Universal); records are always stored in FDI.",
            "Turn the tap sound on or off in Settings › Tooth tap sound."
        ),
        "Turning is switched off when Android's \"Remove animations\" accessibility setting is on."
    ),
    GuideArticle(
        "record", "3D teeth", "Record treatment for one tooth or several",
        "Dentists record care straight from the 3D model.",
        listOf(
            "Tap a tooth on the model. A Selected tooth card appears under the model with Record treatment and Chart status.",
            "For several teeth, choose Record treatment under the model, tap each tooth (tap again to remove it), then Record treatment (n).",
            "Pick the procedure (or type your own), the status (Completed, In progress or Planned), surfaces if needed, the doctor and the date, and add notes.",
            "Save. The teeth turn blue (completed) or show + (planned), and the entry appears in Treatment history.",
            "Chart status on the Selected tooth card records presence (missing, extracted…), findings and restorations for that tooth."
        ),
        "Recording treatment needs the \"Record & edit treatment\" access item. Without it the model is view only."
    ),
    GuideArticle(
        "edit-treatment", "3D teeth", "Edit or correct a recorded treatment",
        "Any recorded treatment can be changed later; the old values are kept.",
        listOf(
            "Open the patient and, in Treatment history below the model, tap the tooth to expand its timeline.",
            "Tap Edit on the entry you want to change.",
            "Change the procedure, status (including Cancelled), surfaces, doctor, date or notes, then Save.",
            "The change log of that treatment keeps the previous values, and the patient History shows what changed. Moving a completed treatment to another status removes its 'treated' colour."
        ),
        "A treatment can't be moved to another tooth; record a new one for the right tooth and cancel the wrong one."
    ),
    GuideArticle(
        "treatment-payments", "3D teeth", "Take payments for a treatment over several visits",
        "Set the total fee once, then add each visit's payment (e.g. root canal 5,000 tk, 1,000 tk per visit).",
        listOf(
            "Open the patient and, in Treatment history below the 3D model, tap the tooth to expand its timeline.",
            "Tap Set fee / add payment on the treatment (for example Root canal).",
            "Enter the total fee for the treatment (e.g. 5000). If the procedure matches one of your Services, its default fee is filled in.",
            "Enter the amount paid at this visit (e.g. 1000), choose Cash, bKash, Nagad, Card or Bank, check the date and add a note such as \"visit 1 of 5\". Tap Save.",
            "At every later visit tap Add payment and enter that visit's amount; the last amount and method are filled in for you.",
            "The treatment shows Fee · Paid · Due (amber while money is due, green when fully paid). Earlier payments are listed with a Receipt button to share each one.",
            "If the treatment ends up costing more or less, change the total fee in the same dialog (it can't go below what was already paid)."
        ),
        "The fee and payments are an invoice in the patient's Billing tab too, so receipts, refunds and Reports include them. The patient sees Fee, Paid and Due in the patient app. Needs the \"Invoices & payments\" access item."
    ),
    GuideArticle(
        "full-3d", "3D teeth", "Use the full-screen 3D chart",
        "A larger chart with views, arch filters, roots and surface selection.",
        listOf(
            "Tap Full 3D chart under the patient's model (or Open interactive 3D chart in the Chart tab).",
            "It turns slowly after loading. Drag to rotate, two fingers to pan, pinch or + / − to zoom, double-tap or Reset to return to the front view.",
            "Choose Front, Upper, Lower, Left or Right views, and Both / Upper / Lower to show arches. 123 shows tooth numbers; Root shows roots inside a see-through gum.",
            "Tap a tooth to see its record. Pick Whole tooth or a surface on the pad, then Record treatment to open the recorder in the app.",
            "Press Back to return to the patient. Read-only charts (history, patient app) can't record."
        ),
        null
    ),
    GuideArticle(
        "chart", "Clinical care", "Assess teeth in the Chart tab (2D)",
        "Record assessment, presence, findings and restorations as separate facts.",
        listOf(
            "Open a patient › Chart. Choose Permanent, Primary or Mixed dentition.",
            "Tap a tooth in the odontogram. Choose its assessment and presence, and a replacement if applicable.",
            "Choose Whole tooth or a surface (M, D, F, L, I/O), then Add finding or Add restoration.",
            "Optionally enter a Planned procedure and a clinical note, then Save assessment.",
            "Use Resolve findings at selected scope only when the finding has really been resolved. Patient explanation view shows planned and completed care for a chairside talk."
        ),
        "An unrecorded tooth is unassessed, never automatically healthy. Record missing or extracted teeth explicitly."
    ),
    GuideArticle(
        "encounters", "Clinical care", "Write and finalize an encounter",
        "Keep an examination note and preserve later corrections.",
        listOf(
            "Open Encounters and choose + New encounter. Record the clinician, complaint, examination, diagnosis and plan.",
            "Use Edit draft while it is a draft. Review it, then Finalize.",
            "To correct a finalized note choose Amend with reason and enter the reason and the correction.",
            "Amendments stay attached to the original note; History records the change."
        ),
        null
    ),
    GuideArticle(
        "treatments", "Clinical care", "Move treatment through its steps",
        "The Treatments tab shows every planned and performed item with its status.",
        listOf(
            "Open Treatments to see the treatment plan for this patient.",
            "Choose the next state: Accepted, In progress, Completed, Deferred, Declined, Referred or Cancelled. Record the clinician or reason when asked.",
            "For Completed care record the outcome; the tooth turns blue on the 3D model.",
            "Use Amend with reason on a performed record to correct it, or Edit in the 3D Treatment history to change any field."
        ),
        "Completing care doesn't resolve findings automatically; reassess the tooth in Chart status."
    ),
    GuideArticle(
        "history", "Clinical care", "Review history and earlier charts",
        "Compare previous states and show them in 3D.",
        listOf(
            "Open the History tab to see every change with date and author.",
            "Use Compare chart on a change to see the earlier and current chart side by side.",
            "Use View earlier chart in 3D to inspect that earlier state read-only."
        ),
        null
    ),
    GuideArticle(
        "slots", "Appointments", "Open appointment slots for a day",
        "Make a doctor's times bookable in a few taps.",
        listOf(
            "Open Schedule (or ☰ › Appointments) and keep Calendar selected.",
            "Pick a day on the month calendar (green dot = free slots, grey dot = fully booked).",
            "Tap Open slots on <day>. Choose the doctor, the hours (Morning, Afternoon, Evening, Full day or exact times), the appointment length, and This day only or every week on that weekday (4 weeks to 1 year).",
            "Check the preview (e.g. \"8 slots: 09:00, 09:30…\") and tap Open n slots. They are shared with staff and the patient app at once.",
            "Under the day you see each doctor's hours: Close this day removes one day of a weekly series; Remove / Delete all repeats removes the hours. All availability lists everything with Pause and Remove.",
            "The Online booking by patients switch at the top turns patient self-booking on or off."
        ),
        "Opening slots needs the \"Open & close appointment slots\" access item. Doctors to choose from come from Team & roles."
    ),
    GuideArticle(
        "booking", "Appointments", "Book, reschedule or cancel an appointment",
        "Book from a free slot, or add any appointment from the list.",
        listOf(
            "In Schedule › Calendar, pick a day and tap a free (outlined) time.",
            "Find the patient by name, phone or patient app ID, enter the reason or service, and tap Book slot. The time shows the patient's name and is taken for everyone at once.",
            "Tap a booked time to open that patient.",
            "Schedule › List shows appointments by Day, Week, Month or All. Use + Book appointment for a time outside the slots, Reschedule / edit to change one, and the status buttons (Confirmed, Checked in, In progress, Completed, Cancelled, No show) as the visit goes on.",
            "Bookings made by patients in the patient app arrive with the next Sync. If the time was taken meanwhile it shows as Requested: tap Confirmed (after rescheduling if needed) or Cancelled, and call the patient."
        ),
        "Booking needs the \"Book & change appointments\" access item."
    ),
    GuideArticle(
        "reminders", "Appointments", "Appointment reminders",
        "Reminders are scheduled on each phone; nothing is sent from a server.",
        listOf(
            "In Settings › Clinic management › Reminders, turn on clinic reminders and set the hours before the appointment and quiet hours.",
            "On a patient's Overview turn on Appointment reminders (patient consent). When booking, keep Schedule appointment reminder on.",
            "In Settings choose Allow appointment notifications and allow them when Android asks.",
            "Patients who use the patient app get reminders on their own phone the day before and 2 hours before."
        ),
        "Lock screens show only a general reminder. No SMS or email is sent."
    ),
    GuideArticle(
        "team", "Team & roles", "Add staff and set their roles and access",
        "Each person gets a role and simple on/off access switches.",
        listOf(
            "Open ☰ › Team & roles (or Settings › Team & roles).",
            "Tap Add staff member. Enter the name and the Google email they will sign in with, plus phone and registration if you like.",
            "Choose a role: Admin, Dentist, Assistant, Receptionist, Cashier or Auditor. This fills in their access.",
            "Switch any access item on or off: view charts, record & edit treatment, register patients, book appointments, open & close slots, invoices & payments, patient app logins, reports, clinic settings & team.",
            "Tap Add to team (or Save changes). It is shared with the clinic at once. When the person signs in with Google they join your clinic with exactly that access.",
            "Turn off Active to remove someone's access, or Remove from team. Their earlier work stays in the records."
        ),
        "Only the owner and people with \"Clinic settings, team & roles\" can change the team."
    ),
    GuideArticle(
        "patient-logins", "Team & roles", "Give a patient access to the patient app",
        "You choose a simple Patient ID and password for the patient.",
        listOf(
            "Open the patient › Overview › Patient app access › Create patient login.",
            "Type a Patient ID (4–12 letters or numbers, e.g. RAHIM01) and a password (6+ characters), or keep the suggestions.",
            "Tap Create login and share the ID and password with the patient (Copy or Share). The password is shown only once.",
            "To change the password or ID use Change ID / password; the old password stops working at once. Turn off removes app access.",
            "The patient's record shows Patient app ID. Use it to find them when booking."
        ),
        "Patient logins don't use any Firebase account. Choose passwords that aren't easy to guess. Needs the \"Patient app logins\" access item."
    ),
    GuideArticle(
        "billing", "Billing", "Create an invoice and receive payment",
        "Record line items and distinguish billed amounts from money received.",
        listOf(
            "Open the patient's Billing tab and choose + Create invoice.",
            "Pick a service or type a line description, unit price and quantity; choose Add line for each item. Set discount and tax, then Issue invoice.",
            "Choose Receive payment on an unpaid invoice and enter the amount, method and optional reference. Partial payments leave a balance.",
            "Use Share receipt or Share invoice to send a copy. Payments for one treatment over several visits are easier from Treatment history (see Take payments for a treatment over several visits).",
            "To correct: Refund a payment (amount, method and reason), or Void an invoice with a reason after refunding its payments."
        ),
        "Recording a payment doesn't charge a card. Needs the \"Invoices & payments\" access item."
    ),
    GuideArticle(
        "reports", "Reports", "Review and share practice reports",
        "Compare activity and finances for a period.",
        listOf(
            "Open Reports and choose Day, Week, Month, Custom or All (for Custom pick the first and last dates).",
            "Read billed, received, refunded, net and outstanding amounts, appointment states, clinician workload and treatment activity.",
            "Treatment payments shows money received for treatments in the period (by payment method), every treatment with a fee (fees, paid, due, fully paid), totals by procedure, and the largest balances still due.",
            "Choose Share financial report CSV to share it."
        ),
        "Reports use the records on this device; Sync first for the latest. Needs the \"Reports\" access item."
    ),
    GuideArticle(
        "images", "Records and sync", "Attach and view clinical images",
        "Images are stored without losing detail and kept small.",
        listOf(
            "Open the patient › Overview › Attach image and pick a photo or X-ray.",
            "The app compresses it losslessly (WebP or PNG) to 512 KB or less, scaling down only if it can't fit otherwise.",
            "Sync to save the image reference to the shared record.",
            "Tap an image under Clinical images to view it."
        ),
        null
    ),
    GuideArticle(
        "sync", "Records and sync", "Sync, conflicts and restore",
        "Know what is on this device and what the clinic has.",
        listOf(
            "Tap Sync at the top. It sends your saved changes, applies patient app bookings, fetches others' changes and updates patients' app copies.",
            "If another device changed the same patient, use Review conflict · retain draft and load server version. Your draft is kept under Conflict drafts in Settings for export.",
            "Restore cloud records reloads all clinic records onto this device (sync or resolve your changes first).",
            "Export care summary PDF or Export JSON from a patient's Overview; Import patient JSON as a new record is in Settings."
        ),
        "Exported files contain sensitive patient information; choose the destination carefully."
    ),
    GuideArticle(
        "subscription", "Settings", "Subscription price and account",
        "One subscription covers the whole clinic and is priced by the number of patients; patients never pay.",
        listOf(
            "Price: up to 1,000 patients US\$5 a month during the launch offer (normally US\$10). Each further 1,000 patients adds US\$5 (normally US\$10): 1,001–2,000 patients US\$10, 2,001–3,000 US\$15, and so on. Google Play shows the price in your currency.",
            "The owner or an admin subscribes from the paywall or Settings › Subscription. The plan offered matches your current number of active (not archived) patients. Every staff device of the clinic then unlocks.",
            "Settings › Subscription shows how many patients your plan covers. When it is almost full, Home shows a reminder.",
            "When the plan is full, registering a new patient asks you to upgrade. Tap Upgrade in Settings › Subscription on the phone that bought the subscription; Google Play charges only the difference for the rest of the month. Archiving patients who no longer visit also frees places.",
            "Use Restore purchase after reinstalling, and Manage in Google Play to cancel. Super admin accounts and their clinics are free.",
            "Sign out from ☰ › Sign out (sync first). Settings also has Tooth numbering, Tooth tap sound and notification permission."
        ),
        null
    ),
    GuideArticle(
        "patient-signin", "Patient app", "Sign in to the patient app",
        "Use the Patient ID and password your clinic gave you.",
        listOf(
            "Open Dental Care and choose Patient on the sign-in screen.",
            "Type your Patient ID and password exactly as given by the clinic, then Sign in.",
            "Forgot your password? Ask your clinic; they can set a new one. The old one then stops working.",
            "Sign out with Sign out at the top right or in the ☰ menu."
        ),
        null, "patient"
    ),
    GuideArticle(
        "patient-teeth", "Patient app", "See your teeth and treatment history",
        "Your clinic's records shown in 3D, read only.",
        listOf(
            "Each treatment shows its fee, what you have paid and what is still due.",
            "After signing in you see your teeth in 3D. Drag to rotate, pinch to zoom; it turns slowly on its own.",
            "Blue teeth were treated, + means planned care, amber means a finding.",
            "Tap a tooth to see its treatment history below the model.",
            "Use ☰ to jump to My teeth, Treatment history, My appointments, Book appointment or Billing. Refresh my record loads the latest from the clinic."
        ),
        null, "patient"
    ),
    GuideArticle(
        "patient-book", "Patient app", "Book or cancel an appointment",
        "Pick a free time from your clinic's calendar.",
        listOf(
            "Open ☰ › Book appointment. Days with free times have a green dot.",
            "Pick a day, tap a free time, add a reason if you like and tap Book.",
            "Your booking appears under Upcoming appointments straight away and is sent to the clinic.",
            "To cancel, tap Cancel on the appointment (up to 2 hours before). Later than that, please call the clinic.",
            "You can hold up to 3 upcoming bookings."
        ),
        "If online booking is off, call the clinic to book.", "patient"
    ),
    GuideArticle(
        "patient-reminders", "Patient app", "Appointment reminders on your phone",
        "Your phone reminds you before each appointment.",
        listOf(
            "Allow notifications when the app asks (or in Android Settings › Apps › Dental Care › Notifications).",
            "Reminders come the day before and 2 hours before each upcoming appointment.",
            "Open the app now and then so the reminders follow any changes your clinic made."
        ),
        null, "patient"
    ),
)

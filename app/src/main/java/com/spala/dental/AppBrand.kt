package com.spala.dental

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** The clinic logo, or the Dental Care vector artwork. Vector artwork remains crisp at toolbar and tablet sizes. Adjacent text supplies the label. */
@Composable
fun DentalAppLogo(modifier: Modifier = Modifier) {
    // The clinic's own logo once an admin sets it (Settings › Clinic branding).
    ClinicLogo(modifier.size(40.dp))
}

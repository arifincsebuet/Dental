package com.spala.dental

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Vector artwork remains crisp at toolbar and tablet sizes. Adjacent text supplies the label. */
@Composable
fun DentalAppLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_dental_logo),
        contentDescription = null,
        modifier = modifier.size(40.dp)
    )
}

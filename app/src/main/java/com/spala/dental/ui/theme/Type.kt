package com.spala.dental.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Family = FontFamily.SansSerif

/** A compact, high-contrast type scale tuned for dense clinical screens. */
val Typography = Typography(
    displaySmall = TextStyle(fontFamily = Family, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Family, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Family, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Family, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontFamily = Family, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(fontFamily = Family, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
)

package dev.charanjeev.bahi.core.designsystem.theme

import androidx.compose.ui.graphics.Color

internal val Green40 = Color(0xFF2E7D5B)
internal val Green80 = Color(0xFF9BD5B8)
internal val Sand40 = Color(0xFF7A5C2E)
internal val Sand80 = Color(0xFFE5C79A)
internal val Red40 = Color(0xFFB3261E)
internal val Red80 = Color(0xFFF2B8B5)

// Amber, for the "approaching a limit" state that sits between normal and
// error. Deliberately not taken from MaterialTheme.colorScheme: dynamic
// colour derives every slot from the user's wallpaper, so `tertiary` and
// `secondary` are whatever hue that produces -- on a blue wallpaper they read
// as another shade of the same blue as `primary`, which is exactly the
// distinction this colour exists to make. `error` is allowed to be
// semantically fixed for the same reason; warning needs the same treatment
// and Material3 has no slot for it.
internal val Amber40 = Color(0xFF8A5300)
internal val Amber80 = Color(0xFFFFB868)

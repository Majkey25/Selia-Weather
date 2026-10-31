package cz.majkey.pocasicesko.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import cz.majkey.pocasicesko.data.WeatherKind

internal data class RenderedAppearance(val palette: WeatherPalette, val colors: ColorScheme, val dark: Boolean)
internal val LocalWeatherAppearance = staticCompositionLocalOf {
    val palette = appearancePalette(AppAppearance.WEATHER, null, true)
    RenderedAppearance(palette, appearanceColors(palette, AppAppearance.WEATHER, true), true)
}

@Composable
internal fun SheetSystemBars() {
    val view = LocalView.current
    val lightBars = MaterialTheme.colorScheme.onSurface.luminance() <= 0.5f
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }
}

internal fun renderedAppearance(context: Context, config: AppearanceConfig, kind: WeatherKind?, isDay: Boolean): RenderedAppearance {
    val settings = config.normalized()
    val dark = settings.isDark(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    val theme = settings.theme(dark)
    var palette = appearancePalette(theme, kind, isDay, dark, settings.custom)
    val colors = if (theme == AppAppearance.MATERIAL && Build.VERSION.SDK_INT >= 31) {
        (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).also {
            palette = WeatherPalette(listOf(it.background, it.background))
        }
    } else appearanceColors(palette, theme, dark, settings.custom)
    return RenderedAppearance(palette, colors, colors.onBackground.luminance() > 0.5f)
}

@Composable
fun WeatherTheme(
    config: AppearanceConfig? = null,
    kind: WeatherKind? = null,
    isDay: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val settings = (config ?: AppearanceSettings.load(context)).normalized()
    val systemDark = isSystemInDarkTheme()
    val rendered = renderedAppearance(context, settings.copy(mode = if (settings.isDark(systemDark)) AppearanceMode.DARK else AppearanceMode.LIGHT), kind, isDay)
    val family = when (settings.font) {
        AppearanceFont.SYSTEM -> FontFamily.SansSerif
        AppearanceFont.SERIF -> FontFamily.Serif
        AppearanceFont.MONOSPACE -> FontFamily.Monospace
    }
    val typography = Typography().let { base -> base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = family), displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family), headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family), headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family), titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family), bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family), bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family), labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family)) }
    val density = LocalDensity.current
    SideEffect {
        context.findActivity()?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !rendered.dark
                isAppearanceLightNavigationBars = !rendered.dark
            }
        }
    }
    CompositionLocalProvider(LocalWeatherAppearance provides rendered,
        LocalDensity provides Density(density.density, density.fontScale * settings.textScale)) {
        MaterialTheme(colorScheme = rendered.colors, typography = typography,
            shapes = Shapes(large = RoundedCornerShape(settings.cornerRadius.dp),
                medium = RoundedCornerShape((settings.cornerRadius / 2).dp)), content = content)
    }
}

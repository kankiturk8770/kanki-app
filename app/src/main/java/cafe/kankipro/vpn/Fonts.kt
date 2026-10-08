package cafe.kankipro.vpn

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

// ------------------------------------------------------------------ فونت‌ها
// فایل‌های فونت (پوشه‌ی app/src/main/res/font) هنگام ساخت دانلود می‌شوند:
//   • روی گیت‌هاب: مرحله‌ی «دریافت فونت‌ها» در build.yml
//   • روی اندروید استودیو: تسک fetchAssets در app/build.gradle.kts
// اگر به هر دلیل دانلود نشده باشند، برنامه به‌جای خطای ساخت یا بسته‌شدن،
// از فونت پیش‌فرض اندروید استفاده می‌کند.

/** شناسه‌ی فونت را با اسمش پیدا می‌کند؛ اگر فایلش نبود صفر برمی‌گرداند */
private fun fontRes(name: String): Int =
    runCatching { Class.forName("cafe.kankipro.vpn.R\$font").getField(name).getInt(null) }.getOrDefault(0)

private fun fontFamilyOf(vararg parts: Pair<String, FontWeight>): FontFamily {
    val fonts = parts.mapNotNull { (name, weight) ->
        fontRes(name).takeIf { it != 0 }?.let { Font(it, weight) }
    }
    return if (fonts.isEmpty()) FontFamily.Default else FontFamily(fonts)
}

val Vazir: FontFamily = fontFamilyOf(
    "vazir_regular" to FontWeight.Normal,
    "vazir_bold" to FontWeight.Bold,
    "vazir_black" to FontWeight.Black,
)

val Poppins: FontFamily = fontFamilyOf(
    "poppins_semibold" to FontWeight.SemiBold,
    "poppins_bold" to FontWeight.Bold,
)

@OptIn(ExperimentalTextApi::class)
val Orbitron: FontFamily = fontRes("orbitron").takeIf { it != 0 }?.let { id ->
    FontFamily(Font(id, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))))
} ?: FontFamily.Default

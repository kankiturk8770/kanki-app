package cafe.kankipro.vpn

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// اجزای مشترک طراحی جدید (کارت، دکمه‌ی چندگزینه‌ای، پرچم، پینگ و …)

/** متن فقط لاتین/عدد است؟ (برای انتخاب فونت Poppins و جهت چپ‌به‌راست) */
fun isLatin(s: String) = s.any { it.isLetterOrDigit() } && s.none { it in '؀'..'ۿ' }

/** متن لاتین (عدد و واحد) را وسط جمله‌ی فارسی جدا نگه می‌دارد تا جابه‌جا نشود */
fun ltr(s: String) = "\u2066" + s.replace(' ', '\u00A0') + "\u2069"

/** متنی که خودش فونت مناسب (فارسی یا لاتین) را انتخاب می‌کند */
@Composable
fun AutoText(text: String, size: TextUnit = 14.sp, color: Color = K.Text, weight: FontWeight = FontWeight.Bold, modifier: Modifier = Modifier) {
    val latin = isLatin(tr(text))
    T(text, size = size, color = color, weight = weight, font = if (latin) Poppins else Vazir, ltr = latin, modifier = modifier)
}

/** کارت اصلی طراحی جدید: شیشه‌ی تیره با لبه‌ی طلایی ملایم */
@Composable
fun KCard(
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    radius: Dp = 24.dp,
    border: Color = K.Gold.copy(alpha = .22f),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(K.Surface, K.Surface2)))
            .border(1.dp, Brush.verticalGradient(listOf(border, border.copy(alpha = border.alpha * .35f))), shape)
            .padding(padding),
        content = content,
    )
}

/** یک گزینه‌ی دکمه‌ی چندگزینه‌ای */
data class SegItem(val key: String, val label: String, val icon: String? = null, val enabled: Boolean = true)

/** دکمه‌ی چندگزینه‌ای (مثل انتخاب پروتکل)؛ گزینه‌ی انتخاب‌شده طلایی می‌شود */
@Composable
fun Segmented(items: List<SegItem>, selected: String, modifier: Modifier = Modifier, enabled: Boolean = true, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(shape)
            .background(K.Panel.copy(alpha = .85f))
            .border(1.dp, K.Gold.copy(alpha = .16f), shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            val sel = item.key == selected
            val on = enabled && item.enabled
            val bg by animateColorAsState(if (sel) K.Gold else Color.Transparent, tween(220), label = "seg")
            val tc = when {
                sel -> K.OnGold
                on -> K.Text
                else -> K.Sub.copy(alpha = .45f)
            }
            Row(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                    .background(if (sel) Brush.verticalGradient(listOf(K.Shine, K.Gold, K.GoldDeep)) else Brush.verticalGradient(listOf(bg, bg)))
                    .clickable(enabled = on && !sel) { onSelect(item.key) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.icon?.let { ic -> MiniIcon(ic, tc, 13.dp); Spacer(Modifier.width(4.dp)) }
                AutoText(item.label, size = 12.sp, color = tc, weight = if (sel) FontWeight.Black else FontWeight.Bold)
            }
        }
    }
}

/** پرچم کشور داخل یک دایره‌ی تیره با حلقه‌ی طلایی */
@Composable
fun FlagBadge(flag: String, size: Dp = 44.dp, ring: Color = K.Gold.copy(alpha = .55f)) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.radialGradient(listOf(K.Surface, K.Panel)))
            .border(1.5.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) { T(flag, size = (size.value * .46f).sp, weight = FontWeight.Normal) }
}

/** نشان پینگ: سبز (خوب)، طلایی (متوسط)، قرمز (بد) */
@Composable
fun PingChip(ms: Long?, modifier: Modifier = Modifier) {
    if (ms == null) return
    val c = pingColor(ms)
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(c.copy(alpha = .12f)).border(1.dp, c.copy(alpha = .45f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(c, 5.dp)
        Spacer(Modifier.width(4.dp))
        T(if (ms < 0) "✕" else "${ms}ms", size = 10.sp, color = c, font = Poppins, weight = FontWeight.Bold, ltr = true)
    }
}

/** فلش «ادامه» که با جهت صفحه (فارسی/انگلیسی) برمی‌گردد */
@Composable
fun Chevron(color: Color = K.Sub, size: Dp = 14.dp) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * .14f
        val x0 = if (rtl) w * .62f else w * .38f
        val x1 = if (rtl) w * .32f else w * .68f
        drawLine(color, Offset(x0, w * .2f), Offset(x1, w * .5f), sw, StrokeCap.Round)
        drawLine(color, Offset(x1, w * .5f), Offset(x0, w * .8f), sw, StrokeCap.Round)
    }
}

/** چرخنده‌ی کوچک «در حال انجام» */
@Composable
fun Spinner(size: Dp = 16.dp, color: Color = K.Gold) {
    val t = rememberInfiniteTransition(label = "sp")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(850, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(size)) {
        rotate(a) { drawArc(color, 0f, 270f, false, style = Stroke(this.size.width * .14f, cap = StrokeCap.Round)) }
    }
}

/** دکمه‌ی گرد آیکون‌دار */
@Composable
fun RoundIcon(size: Dp = 40.dp, tint: Color = K.Gold, filled: Boolean = false, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (filled) Brush.verticalGradient(listOf(tint.copy(alpha = .30f), tint.copy(alpha = .10f))) else Brush.verticalGradient(listOf(K.Surface, K.Panel)))
            .border(1.dp, tint.copy(alpha = .55f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** نوار پیشرفت باریک */
@Composable
fun ProgressLine(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 5.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "pl")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(Color(0x1FFFFFFF))) {
        Box(
            Modifier.fillMaxWidth(f.coerceAtLeast(.02f)).fillMaxHeight().clip(RoundedCornerShape(height))
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = .65f), color))),
        )
    }
}

/** خط جداکننده‌ی عمودی */
@Composable
fun VDivider(height: Dp = 30.dp) {
    Box(Modifier.width(1.dp).height(height).background(K.Gold.copy(alpha = .14f)))
}

/** خط جداکننده‌ی افقی */
@Composable
fun HDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(K.Gold.copy(alpha = .10f)))
}

/** دکمه‌ی کوچک طلایی */
@Composable
fun SmallGold(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.height(38.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(K.Shine, K.Gold, K.GoldDeep)))
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, size = 13.sp, color = K.OnGold, weight = FontWeight.Black) }
}

/** دکمه‌ی کوچک خطی */
@Composable
fun SmallLine(text: String, modifier: Modifier = Modifier, color: Color = K.Gold, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.height(38.dp).clip(shape).background(color.copy(alpha = .08f))
            .border(1.dp, color.copy(alpha = .6f), shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, size = 13.sp, color = color, weight = FontWeight.Bold) }
}

/** عنوان یک بخش با زیرعنوان اختیاری */
@Composable
fun Heading(title: String, sub: String? = null, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, size = 20.sp, color = K.Text, weight = FontWeight.Black)
            sub?.let { T(it, size = 12.sp, color = K.Sub, weight = FontWeight.Normal) }
        }
        trailing()
    }
}

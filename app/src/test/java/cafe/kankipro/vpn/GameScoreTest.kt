package cafe.kankipro.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** امتیاز انتخاب سرور گیمینگ: پینگ + لرزش×۳ + افت×۲۰ (کمتر بهتر) */
class GameScoreTest {
    @Test
    fun flatPathBeatsAFasterJumpyOne() {
        // ۶۰ صاف: امتیاز ۶۰
        val flat = Modes.gameScore(List(8) { 60L })!!
        assertEquals(60.0, flat, 0.001)
        // میانگین ۵۰ ولی بین ۲۰ و ۸۰ می‌پرد: لرزش ۶۰ ← ۵۰ + ۱۸۰
        val jumpy = Modes.gameScore(listOf(20L, 80L, 20L, 80L, 20L, 80L, 20L, 80L))!!
        assertEquals(230.0, jumpy, 0.001)
        assertTrue(flat < jumpy)
    }

    @Test
    fun lossCountsTwentyPerPercent() {
        // یک بسته از ده گم: ۱۰٪ ← ۲۰۰
        val s = Modes.gameScore(listOf(40L, 40L, 40L, null, 40L, 40L, 40L, 40L, 40L, 40L))!!
        assertEquals(40.0 + 200.0, s, 0.001)
        assertNull(Modes.gameScore(listOf(null, null)))
        assertNull(Modes.gameScore(emptyList()))
    }

    @Test
    fun stabilityLabels() {
        // ۲۰ بسته، همه صاف: پایدار و ۰٪
        val flat = Modes.gameQ(List(20) { 60L })!!
        assertEquals(0, flat.loss)
        assertEquals(0, Modes.stability(flat))
        // یک بسته‌ی گم‌شده از ۲۰ (۵٪): متوسط (شاید شانسی)؛ دو تا (۱۰٪): ضعیف
        val one = Modes.gameQ(List(19) { 60L } + listOf(null))!!
        assertEquals(5, one.loss)
        assertEquals(1, Modes.stability(one))
        assertEquals(2, Modes.stability(Modes.gameQ(List(18) { 60L } + listOf(null, null))!!))
        // لرزش ۲۰ بدون افت: متوسط
        val mid = Modes.gameQ(List(20) { if (it % 2 == 0) 60L else 80L })!!
        assertEquals(1, Modes.stability(mid))
        // سنجش کوتاه (۴ بار، Hysteria2/microtun): یک بی‌جواب «متوسط»، نه «ضعیف»؛ نصف «ضعیف»
        assertEquals(1, Modes.stability(Modes.gameQ(listOf(60L, 61L, null, 60L))!!))
        assertEquals(2, Modes.stability(Modes.gameQ(listOf(60L, null, null, 60L))!!))
        assertEquals(0, Modes.stability(Modes.gameQ(listOf(60L, 61L, 62L, 60L))!!))
        assertTrue(Modes.gameQ(listOf(60L, 61L, 62L, 60L))!!.n < Modes.LOSS_MIN_SAMPLES)
    }
}

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
}

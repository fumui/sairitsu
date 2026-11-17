package my.fumui.sairitsu.gameplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchForwardingTest {
    private class Call(val timeNanos: Long, val count: Int, val data: List<Float>)

    /** A MotionEvent stand-in: [pointers] are (id, x, y) now; [history] are (time, per-pointer x, y) older samples. */
    private class FakeEvent(
        override val actionMasked: Int,
        override val actionIndex: Int,
        private val ids: List<Int>,
        private val xs: List<Float>,
        private val ys: List<Float>,
        override val eventTimeNanos: Long,
        private val history: List<Triple<Long, List<Float>, List<Float>>> = emptyList(),
    ) : PointerSource {
        override val pointerCount get() = ids.size
        override val historySize get() = history.size
        override fun historicalTimeNanos(h: Int) = history[h].first
        override fun pointerId(i: Int) = ids[i]
        override fun x(i: Int) = xs[i]
        override fun y(i: Int) = ys[i]
        override fun historicalX(i: Int, h: Int) = history[h].second[i]
        override fun historicalY(i: Int, h: Int) = history[h].third[i]
    }

    private fun forward(event: PointerSource): List<Call> {
        val calls = mutableListOf<Call>()
        TouchForwarder.forward(event) { t, n, d -> calls += Call(t, n, d.take(n * 4)) }
        return calls
    }

    @Test
    fun packsPointersAndHistory() {
        val event = FakeEvent(
            MOVE, 0, listOf(4, 9), listOf(30f, 130f), listOf(40f, 140f), 2_000_000,
            history = listOf(Triple(1_000_000L, listOf(10f, 110f), listOf(20f, 120f))),
        )
        val calls = forward(event)
        assertEquals(listOf(1_000_000L, 2_000_000L), calls.map { it.timeNanos })
        assertEquals(listOf(2, 2), calls.map { it.count })
        assertEquals(listOf(4f, 1f, 10f, 20f, 9f, 1f, 110f, 120f), calls[0].data)
        assertEquals(listOf(4f, 1f, 30f, 40f, 9f, 1f, 130f, 140f), calls[1].data)
    }

    @Test
    fun upAndCancelMapToPhases() {
        val up = forward(FakeEvent(POINTER_UP, 1, listOf(4, 9), listOf(30f, 130f), listOf(40f, 140f), 5))
        assertEquals(1, up.size)
        assertEquals(listOf(9f, 2f, 130f, 140f), up[0].data)
        val only = forward(FakeEvent(UP, 0, listOf(4), listOf(30f), listOf(40f), 6))
        assertEquals(listOf(4f, 2f, 30f, 40f), only[0].data)
        val cancel = forward(FakeEvent(CANCEL, 0, listOf(4, 9), listOf(30f, 130f), listOf(40f, 140f), 7))
        assertEquals(listOf(4f, 3f, 30f, 40f, 9f, 3f, 130f, 140f), cancel[0].data)
        val down = forward(FakeEvent(POINTER_DOWN, 1, listOf(4, 9), listOf(30f, 130f), listOf(40f, 140f), 8))
        assertEquals(listOf(9f, 0f, 130f, 140f), down[0].data)
        assertEquals(listOf(4f, 0f, 30f, 40f), forward(FakeEvent(DOWN, 0, listOf(4), listOf(30f), listOf(40f), 9))[0].data)
    }

    @Test
    fun otherActionsAreIgnored() {
        assertEquals(0, forward(FakeEvent(7, 0, listOf(4), listOf(30f), listOf(40f), 9)).size) // ACTION_HOVER_MOVE
    }

    @Test
    fun phaseCodesMatchNative() {
        // TouchEvent::Phase { Down, Move, Up, Cancel } in runtime/src/modes/touch.h
        assertArrayEquals(
            intArrayOf(0, 1, 2, 3),
            intArrayOf(TouchPhase.DOWN, TouchPhase.MOVE, TouchPhase.UP, TouchPhase.CANCEL),
        )
    }

    private companion object {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val CANCEL = 3
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
    }
}

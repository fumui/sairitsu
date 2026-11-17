package my.fumui.sairitsu.gameplay

import android.os.Build
import android.view.MotionEvent

/** Phase codes of [NativeRuntime.touch]; they match `TouchEvent::Phase` in runtime/src/modes/touch.h. */
object TouchPhase {
    const val DOWN = 0
    const val MOVE = 1
    const val UP = 2
    const val CANCEL = 3
}

/** The part of a MotionEvent that [TouchForwarder] reads, so the packing is unit-testable. */
interface PointerSource {
    val actionMasked: Int
    val actionIndex: Int
    val pointerCount: Int
    val historySize: Int

    /** CLOCK_MONOTONIC nanoseconds of the event. */
    val eventTimeNanos: Long

    fun historicalTimeNanos(h: Int): Long

    fun pointerId(i: Int): Int

    fun x(i: Int): Float

    fun y(i: Int): Float

    fun historicalX(i: Int, h: Int): Float

    fun historicalY(i: Int, h: Int): Float
}

/** [PointerSource] over a real [MotionEvent]. */
class MotionEventSource(private val e: MotionEvent) : PointerSource {
    override val actionMasked get() = e.actionMasked
    override val actionIndex get() = e.actionIndex
    override val pointerCount get() = e.pointerCount
    override val historySize get() = e.historySize

    // API 34 has nanosecond event times; before, the millisecond uptime (the same CLOCK_MONOTONIC base).
    override val eventTimeNanos get() =
        if (Build.VERSION.SDK_INT >= 34) e.eventTimeNanos else e.eventTime * 1_000_000L

    override fun historicalTimeNanos(h: Int) =
        if (Build.VERSION.SDK_INT >= 34) e.getHistoricalEventTimeNanos(h) else e.getHistoricalEventTime(h) * 1_000_000L

    override fun pointerId(i: Int) = e.getPointerId(i)

    override fun x(i: Int) = e.getX(i)

    override fun y(i: Int) = e.getY(i)

    override fun historicalX(i: Int, h: Int) = e.getHistoricalX(i, h)

    override fun historicalY(i: Int, h: Int) = e.getHistoricalY(i, h)
}

/**
 * Packs a pointer event into [NativeRuntime.touch] calls: `(timeNanos, count, [id, phase, xPx, yPx] per pointer)`.
 * A Down or Up names the pointer that changed; a Cancel names all of them; a Move gives every pointer, once per
 * historical sample (each at its own time, oldest first) and once for the event itself.
 */
object TouchForwarder {
    private const val ACTION_DOWN = 0
    private const val ACTION_UP = 1
    private const val ACTION_MOVE = 2
    private const val ACTION_CANCEL = 3
    private const val ACTION_POINTER_DOWN = 5
    private const val ACTION_POINTER_UP = 6

    fun forward(event: PointerSource, send: (timeNanos: Long, count: Int, data: FloatArray) -> Unit) {
        when (event.actionMasked) {
            ACTION_DOWN, ACTION_POINTER_DOWN -> one(event, TouchPhase.DOWN, send)
            ACTION_UP, ACTION_POINTER_UP -> one(event, TouchPhase.UP, send)
            ACTION_CANCEL -> {
                val n = event.pointerCount
                val data = FloatArray(n * 4)
                for (i in 0 until n) pack(data, i, event.pointerId(i), TouchPhase.CANCEL, event.x(i), event.y(i))
                send(event.eventTimeNanos, n, data)
            }
            ACTION_MOVE -> {
                val n = event.pointerCount
                for (h in 0 until event.historySize) {
                    val data = FloatArray(n * 4)
                    for (i in 0 until n) {
                        pack(data, i, event.pointerId(i), TouchPhase.MOVE, event.historicalX(i, h), event.historicalY(i, h))
                    }
                    send(event.historicalTimeNanos(h), n, data)
                }
                val data = FloatArray(n * 4)
                for (i in 0 until n) pack(data, i, event.pointerId(i), TouchPhase.MOVE, event.x(i), event.y(i))
                send(event.eventTimeNanos, n, data)
            }
        }
    }

    private fun one(event: PointerSource, phase: Int, send: (Long, Int, FloatArray) -> Unit) {
        val i = event.actionIndex
        val data = FloatArray(4)
        pack(data, 0, event.pointerId(i), phase, event.x(i), event.y(i))
        send(event.eventTimeNanos, 1, data)
    }

    private fun pack(data: FloatArray, slot: Int, id: Int, phase: Int, x: Float, y: Float) {
        data[slot * 4] = id.toFloat()
        data[slot * 4 + 1] = phase.toFloat()
        data[slot * 4 + 2] = x
        data[slot * 4 + 3] = y
    }
}

package com.mailhub.core

/** 当前时间，单位是秒、带小数，对应 Python 的 `time.time()`。测试注入可控的实现。 */
fun interface Clock {
    fun now(): Double
}

/** 系统时钟。 */
object SystemClock : Clock {
    override fun now(): Double = System.currentTimeMillis() / 1000.0
}

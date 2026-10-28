package com.germanium.shortcuts

internal object NativeMath {
    init { System.loadLibrary("shortcuts_math") }
    @JvmStatic external fun evaluateNative(expression: String, angleUnit: Int): String
    fun evaluate(expression: String, unit: com.germanium.shortcuts.core.AngleUnit): String = evaluateNative(expression, unit.ordinal)
}

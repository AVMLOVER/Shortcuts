package com.germanium.shortcuts

internal object NativeMath {
    init { System.loadLibrary("shortcuts_math") }
    @JvmStatic external fun evaluateNative(expression: String, angleUnit: Int): String
    @JvmStatic external fun statisticsNative(operation: String, numbers: String): String
    @JvmStatic external fun factorsNative(number: String, operation: String, powers: Boolean): String
    fun evaluate(expression: String, unit: com.germanium.shortcuts.core.AngleUnit): String = evaluateNative(expression, unit.ordinal)
    fun statistics(operation: String, numbers: List<String>): List<String> = statisticsNative(operation, numbers.joinToString("\n")).also { require(!it.startsWith('!')) { throw com.germanium.shortcuts.core.Problem("err_statistics") } }.lines()
}

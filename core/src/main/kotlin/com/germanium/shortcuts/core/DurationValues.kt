package com.germanium.shortcuts.core

fun durationMillis(value: String, unit: String): Long {
    val amount = parseNumber(value)
    val multiplier = when (unit) { "milliseconds" -> 1; "seconds" -> 1000; else -> throw Problem("err_invalid_data") }
    val result = try { amount.multiply(multiplier.toBigDecimal()).longValueExact() } catch (_: ArithmeticException) { throw Problem("err_invalid_data") }
    require(result in 0..60000) { throw Problem("err_invalid_data") }
    return result
}

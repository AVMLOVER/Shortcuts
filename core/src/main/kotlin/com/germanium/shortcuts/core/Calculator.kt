package com.germanium.shortcuts.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.pow

/** Bounded decimal parser; never evaluates JavaScript or arbitrary JVM code. */
class Calculator(private val angleUnit: AngleUnit = AngleUnit.DEGREES, private val lookup: (String) -> BigDecimal = { throw Problem("err_variable_missing") }) {
    fun evaluate(expression: String): BigDecimal {
        require(expression.isNotBlank() && expression.length <= 4096) { throw Problem("err_math") }
        nativeEvaluate?.let { native ->
            val expanded = IDENTIFIERS.replace(expression) { match ->
                if (match.groups[1] != null) match.value else {
                    val name = match.value
                    if (expression.substring(match.range.last + 1).trimStart().startsWith('(') || name in listOf("pi", "e")) name else "(${lookup(name).toPlainString()})"
                }
            }
            require(expanded.length <= 65536) { throw Problem("err_math") }
            val result = native(expanded, angleUnit); require(!result.startsWith('!')) { throw Problem("err_math") }
            return Value.Number(parseNumber(result)).value
        }
        return Parser(expression).parse()
    }
    private inner class Parser(private val source: String) {
        private var index = 0
        private var depth = 0
        fun parse(): BigDecimal = sum().also { skip(); require(index == source.length) { throw Problem("err_math") } }
        private fun skip() { while (index < source.length && source[index].isWhitespace()) index++ }
        private fun take(char: Char): Boolean { skip(); return if (index < source.length && source[index] == char) { index++; true } else false }
        private fun bounded(value: BigDecimal): BigDecimal = Value.Number(value.round(MC)).value
        private fun sum(): BigDecimal {
            var result = product()
            while (true) result = when { take('+') -> bounded(result.add(product(), MC)); take('-') -> bounded(result.subtract(product(), MC)); else -> return result }
        }
        private fun product(): BigDecimal {
            var result = unary()
            while (true) result = when {
                take('*') -> bounded(result.multiply(unary(), MC))
                take('/') -> { val divisor = unary(); require(divisor.signum() != 0) { throw Problem("err_math") }; bounded(result.divide(divisor, MC)) }
                take('%') -> { val divisor = unary(); require(divisor.signum() != 0) { throw Problem("err_math") }; bounded(result.remainder(divisor, MC)) }
                else -> return result
            }
        }
        private fun unary(): BigDecimal {
            require(++depth <= 128) { throw Problem("err_math") }
            try { return when { take('+') -> unary(); take('-') -> unary().negate(); else -> power() }
            } finally { depth-- }
        }
        private fun power(): BigDecimal {
            val base = atom()
            if (!take('^')) return base
            val exponent = unary().intValueExact()
            require(exponent in -100..100) { throw Problem("err_math") }
            val power = bounded(base.pow(kotlin.math.abs(exponent), MC))
            return if (exponent >= 0) power else { require(power.signum() != 0) { throw Problem("err_math") }; bounded(BigDecimal.ONE.divide(power, MC)) }
        }
        private fun atom(): BigDecimal {
            skip()
            if (take('(')) { val result = sum(); require(take(')')) { throw Problem("err_math") }; return result }
            require(index < source.length) { throw Problem("err_math") }
            if (source[index].isLetter() || source[index] == '_') {
                val start = index++
                while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
                val name = source.substring(start, index)
                if (!take('(')) return when (name) { "pi" -> BigDecimal("3.141592653589793238462643383279503"); "e" -> BigDecimal("2.718281828459045235360287471352662"); else -> bounded(lookup(name)) }
                val first = sum()
                val result = when (name) {
                    "abs" -> first.abs()
                    "round", "ceil", "floor" -> {
                        val digits = if (take(',')) sum().intValueExact() else 0
                        require(digits in -100..100) { throw Problem("err_math") }
                        first.setScale(digits, when (name) { "ceil" -> RoundingMode.CEILING; "floor" -> RoundingMode.FLOOR; else -> RoundingMode.HALF_UP })
                    }
                    "root" -> {
                        require(take(',')) { throw Problem("err_math") }; val degree = sum().intValueExact()
                        require(degree in 1..1000 && (first.signum() >= 0 || degree % 2 == 1)) { throw Problem("err_math") }
                        if (degree == 1) first else floating(kotlin.math.abs(first.toDouble()).pow(1.0 / degree).let { if (first.signum() < 0) -it else it })
                    }
                    "min", "max" -> { require(take(',')) { throw Problem("err_math") }; val second = sum(); if (name == "min") first.min(second) else first.max(second) }
                    "gcd", "lcm", "mod" -> {
                        require(take(',')) { throw Problem("err_math") }; val second = sum()
                        if (name == "mod") { require(second.signum() != 0) { throw Problem("err_math") }; first.remainder(second.abs(), MC).let { if (it.signum() < 0) it + second.abs() else it } }
                        else { val a = first.toBigIntegerExact().abs(); val b = second.toBigIntegerExact().abs(); val gcd = a.gcd(b); (if (name == "gcd") gcd else if (a.signum() == 0 || b.signum() == 0) java.math.BigInteger.ZERO else a.divide(gcd).multiply(b)).toBigDecimal() }
                    }
                    "sin", "cos", "tan", "asin", "acos", "atan", "sqrt", "cbrt" -> {
                        val scale = when (angleUnit) { AngleUnit.DEGREES -> kotlin.math.PI / 180.0; AngleUnit.RADIANS -> 1.0; AngleUnit.GRADIANS -> kotlin.math.PI / 200.0 }
                        val number = first.toDouble()
                        val quarter = when (angleUnit) { AngleUnit.DEGREES -> 90.0; AngleUnit.RADIANS -> kotlin.math.PI / 2; AngleUnit.GRADIANS -> 100.0 }
                        val turns = number / quarter
                        val result = if (name in listOf("sin", "cos", "tan") && turns.isFinite() && kotlin.math.abs(turns) <= 1e12 && turns == kotlin.math.floor(turns)) {
                            val quadrant = ((turns % 4 + 4) % 4).toInt()
                            when (name) { "sin" -> listOf(0.0, 1.0, 0.0, -1.0)[quadrant]; "cos" -> listOf(1.0, 0.0, -1.0, 0.0)[quadrant]; else -> { require(quadrant % 2 == 0) { throw Problem("err_math") }; 0.0 } }
                        } else when (name) { "sin" -> kotlin.math.sin(number * scale); "cos" -> kotlin.math.cos(number * scale); "tan" -> kotlin.math.tan(number * scale); "asin" -> kotlin.math.asin(number) / scale; "acos" -> kotlin.math.acos(number) / scale; "atan" -> kotlin.math.atan(number) / scale; "cbrt" -> Math.cbrt(number); else -> kotlin.math.sqrt(number) }
                        floating(result)
                    }
                    else -> throw Problem("err_math")
                }
                require(take(')')) { throw Problem("err_math") }
                return bounded(result)
            }
            val match = NUMBER.find(source, index)?.takeIf { it.range.first == index } ?: throw Problem("err_math")
            index += match.value.length
            return bounded(parseNumber(match.value))
        }
    }
    private fun floating(number: Double): BigDecimal { require(number.isFinite()) { throw Problem("err_math") }; return BigDecimal.valueOf(number).round(MathContext(15, RoundingMode.HALF_EVEN)) }
    companion object {
        @Volatile var nativeEvaluate: ((String, AngleUnit) -> String)? = null
        private val IDENTIFIERS = Regex("([0-9]+(?:\\.[0-9]*)?(?:[eE][+-]?[0-9]+)?|\\.[0-9]+(?:[eE][+-]?[0-9]+)?)|[\\p{L}_][\\p{L}\\p{N}_]*")
        private val MC = MathContext.DECIMAL128
        private val NUMBER = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    }
}

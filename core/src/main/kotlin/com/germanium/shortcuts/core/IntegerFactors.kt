package com.germanium.shortcuts.core

object IntegerFactors {
    @Volatile var native: ((String, String, Boolean) -> String)? = null
    fun calculate(number: Value, operation: String, powers: Boolean): Value {
        require(operation in listOf("prime_factors", "divisors", "is_prime")) { throw Problem("err_factor") }
        val source = number.number().stripTrailingZeros().toPlainString()
        val output = (native ?: throw Problem("err_backend"))(source, operation, powers)
        require(!output.startsWith('!')) { throw Problem(if (output == "!size") "err_size" else "err_factor") }
        return if (operation == "is_prime") Value.BooleanValue(when (output) { "true" -> true; "false" -> false; else -> throw Problem("err_factor") })
            else Value.ListValue(if (output.isEmpty()) emptyList() else output.lines().map { Value.Text(it) })
    }
}

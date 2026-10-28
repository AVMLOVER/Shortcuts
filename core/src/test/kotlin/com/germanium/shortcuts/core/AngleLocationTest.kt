package com.germanium.shortcuts.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AngleLocationTest {
    @Test fun currentDateAndTimeUsesRegionalDefaultsAndCustomFormats() {
        val instant = java.time.Instant.parse("2026-10-28T04:05:06Z")
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        mapOf("zh-CN" to "2026-10-28 12:05:06", "en-US" to "10/28/2026 12:05:06", "en-GB" to "28/10/2026 12:05:06", "de-DE" to "28.10.2026 12:05:06", "ja-JP" to "2026/10/28 12:05:06", "zh-TW" to "2026/10/28 12:05:06").forEach { (tag, expected) ->
            val locale = java.util.Locale.forLanguageTag(tag)
            assertEquals(expected, currentDateTimeText(instant, zone = zone, locale = locale))
            assertEquals(instant.toEpochMilli(), parseCurrentDateTime(expected, zone, locale))
        }
        assertEquals("04:05", currentDateTimeText(instant, "HH:mm", java.time.ZoneOffset.UTC))
    }
    @Test fun currentDateOperationReturnsTextAndRejectsInvalidPatterns() = runBlocking {
        val before = System.currentTimeMillis()
        val date = ActionStep(type = "current_date", parameters = mapOf("format" to "yyyy-MM-dd'T'HH:mm:ss'Z'", "zone" to "UTC"))
        val result = ShortcutEngine().run(Shortcut(name = "Date", steps = listOf(date)), ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status); assertTrue(result.steps.last().value is Value.Text)
        assertTrue(java.time.Instant.parse(result.output).toEpochMilli() in (before - 1000)..System.currentTimeMillis())
        val invalid = ActionStep(type = "current_date", parameters = mapOf("format" to "invalid]"))
        val bad = ShortcutEngine().run(Shortcut(name = "Bad date", steps = listOf(invalid)), ExecutionMode.STANDARD)
        assertEquals(RunStatus.FAILED, bad.status)
    }
    @Test fun standaloneRoundingReturnsANumberForDownstreamVariables() = runBlocking {
        for ((mode, expected) in mapOf("nearest" to "-1.23", "up" to "-1.23", "down" to "-1.24")) {
            val round = ActionStep(type = "round_number", parameters = mapOf("source" to "{{input}}", "rounding" to mode, "digits" to "2", "resultVariable" to "rounded"))
            val steps = listOf(ActionStep(type = "number", parameters = mapOf("value" to "-1.234")), round, ActionStep(type = "get_variable", parameters = mapOf("name" to "rounded")))
            val result = ShortcutEngine().run(Shortcut(name = "Round", steps = steps), ExecutionMode.STANDARD)
            assertEquals(RunStatus.SUCCEEDED, result.status); assertEquals(expected, result.output); assertTrue(result.steps.last().value is Value.Number)
        }
    }
    @Test fun transcendentalOutputsAreCleanAndRootsKeepTheirDomains() {
        val c = Calculator()
        mapOf("sin(30)" to "0.5", "cos(90)" to "0", "asin(0.5)" to "30", "sqrt(9)" to "3", "cbrt(-27)" to "-3", "root(81,4)" to "3", "root(-32,5)" to "-2").forEach { (s, expected) -> assertEquals(s, expected, Value.Number(c.evaluate(s)).text()) }
        assertTrue(c.evaluate("sin(1e-20)").signum() > 0)
        listOf("root(-4,2)", "root(2,0)", "root(2,2.5)", "tan(90)").forEach { source -> assertThrows(Exception::class.java) { c.evaluate(source) } }
    }
    @Test fun roundingUsesDecimalPlacesAndSignedDirections() {
        val c = Calculator()
        mapOf("ceil(1.234,2)" to "1.24", "floor(1.239,2)" to "1.23", "ceil(-1.234,2)" to "-1.23", "floor(-1.234,2)" to "-1.24", "round(1.235,2)" to "1.24", "round(-1.235,2)" to "-1.24", "ceil(123,-1)" to "130", "floor(123,-1)" to "120").forEach { (s, expected) -> assertEquals(s, expected, Value.Number(c.evaluate(s)).text()) }
        assertThrows(Exception::class.java) { c.evaluate("round(1,101)") }
    }
    @Test fun calculateSupportsRootsAndConfigurableRounding() = runBlocking {
        for ((parameters, expected) in listOf(mapOf("operation" to "root", "left" to "81", "right" to "4") to "3", mapOf("operation" to "cbrt", "left" to "-27") to "-3", mapOf("operation" to "round", "left" to "-1.234", "rounding" to "down", "digits" to "2") to "-1.24", mapOf("operation" to "round", "left" to "1.234", "rounding" to "up", "digits" to "2") to "1.24")) {
            val result = ShortcutEngine().run(Shortcut(name = "Math", steps = listOf(ActionStep(type = "calculate", parameters = parameters))), ExecutionMode.STANDARD)
            assertEquals(RunStatus.SUCCEEDED, result.status); assertEquals(expected, result.output)
        }
    }
    private fun near(expected: Double, actual: java.math.BigDecimal) = assertEquals(expected, actual.toDouble(), 1e-10)
    @Test fun unitsApplyToAllSixTrigFunctionsAndNestedExpressions() {
        AngleUnit.entries.forEach { unit ->
            val quarter = when (unit) { AngleUnit.DEGREES -> 90.0; AngleUnit.RADIANS -> kotlin.math.PI / 2; AngleUnit.GRADIANS -> 100.0 }
            val c = Calculator(unit)
            near(1.0, c.evaluate("sin($quarter)")); near(0.0, c.evaluate("cos($quarter)"))
            near(1.0, c.evaluate("tan(${quarter / 2})"))
            near(quarter, c.evaluate("asin(1)")); near(quarter, c.evaluate("acos(0)")); near(quarter / 2, c.evaluate("atan(1)"))
            near(0.5, c.evaluate("sin(asin(0.5))")); near(3.0, c.evaluate("sqrt(9)"))
        }
        near(0.5, Calculator().evaluate("sin(30)"))
    }
    @Test fun settingsRoundTripAndLegacyDefault() {
        val codec = XmlCodec()
        AngleUnit.entries.forEach { unit -> val settings = AppSettings(angleUnit = unit); assertEquals(settings, codec.decodeSettings(codec.encode(settings))) }
        assertEquals(AngleUnit.DEGREES, codec.decodeSettings("<settings version=\"1\" mode=\"STANDARD\"/>".toByteArray()).angleUnit)
    }
    @Test fun bothCalculationActionsUseTheRunUnit() = runBlocking {
        AngleUnit.entries.forEach { unit ->
            val angle = when (unit) { AngleUnit.DEGREES -> "30"; AngleUnit.RADIANS -> "pi/6"; AngleUnit.GRADIANS -> "100/3" }
            val steps = listOf(ActionStep(type = "calculate_expression", parameters = mapOf("expression" to "sin($angle)")), ActionStep(type = "calculate", parameters = mapOf("left" to "{{input}}", "operation" to "asin")))
            val result = ShortcutEngine().run(Shortcut(name = "Angles", steps = steps), ExecutionMode.STANDARD, angleUnit = unit)
            assertEquals(RunStatus.SUCCEEDED, result.status)
            near(Calculator(unit).evaluate(angle).toDouble(), result.steps.last().value.number())
        }
    }
    @Test fun locationOutputRetainsSeparateNumericProperties() = runBlocking {
        val platform = object : PlatformActions by PlatformActions.Unavailable {
            override suspend fun currentLocation(): Value = Value.Dictionary(mapOf("latitude" to Value.Number("12.25".toBigDecimal()), "longitude" to Value.Number("-45.5".toBigDecimal())))
        }
        val location = ActionStep(type = "current_location")
        val source = Shortcut(name = "Location", steps = listOf(location, ActionStep(type = "calculate_expression", parameters = mapOf("expression" to "{{@${location.id}.latitude}}+{{@${location.id}.longitude}}"))))
        val result = ShortcutEngine(builtInActions(platform), platform).run(source, ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status); assertEquals("-33.25", result.output)
        assertTrue(ActionOutputs.properties("current_location").containsAll(listOf("latitude", "longitude")))
    }
}

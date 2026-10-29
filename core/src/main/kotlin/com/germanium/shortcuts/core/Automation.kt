package com.germanium.shortcuts.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

enum class TriggerKind { TIME, BATTERY_BELOW, CHARGING, DISCHARGING, WIFI_CONNECTED, SPECIFIC_WIFI, NFC, NETWORK_CONNECTED, SCREEN_ON, SCREEN_OFF, BOOT }
data class AutomationRule(val id: String = UUID.randomUUID().toString(), val name: String, val shortcutId: String, val trigger: TriggerKind,
    val parameters: Map<String, String> = emptyMap(), val enabled: Boolean = true, val lastMatched: Boolean = false, val lastToken: String = "") {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && shortcutId.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && name.isNotBlank() && name.length <= 200) { throw Problem("err_invalid_data") }
        require(parameters.size <= 16 && parameters.all { it.key.length in 1..64 && it.value.length <= 4096 } && lastToken.length <= 100) { throw Problem("err_size") }
        if (trigger == TriggerKind.TIME) { val value = parameters["time"].orEmpty(); require(Regex("[0-2][0-9]:[0-5][0-9]").matches(value) && value.substring(0, 2).toInt() <= 23) { throw Problem("err_date") } }
        if (trigger == TriggerKind.BATTERY_BELOW) require(parameters["level"]?.toIntOrNull()?.let { it in 1..100 } == true) { throw Problem("err_invalid_data") }
        if (trigger == TriggerKind.SPECIFIC_WIFI) require(parameters["ssid"].orEmpty().isNotBlank()) { throw Problem("err_invalid_data") }
        if (trigger == TriggerKind.NFC) require(parameters["tagId"].orEmpty().matches(Regex("[0-9A-Fa-f]{2,64}"))) { throw Problem("err_invalid_data") }
    }
}
data class DeviceSnapshot(val battery: Int = 100, val charging: Boolean = false, val wifi: Boolean = false, val connected: Boolean = false, val ssid: String = "")
data class TriggerDecision(val fire: Boolean, val rule: AutomationRule)
fun evaluateTrigger(rule: AutomationRule, state: DeviceSnapshot, event: String, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): TriggerDecision {
    if (!rule.enabled) return TriggerDecision(false, rule)
    if (rule.trigger == TriggerKind.NFC) return TriggerDecision(event.equals("nfc:" + rule.parameters["tagId"], ignoreCase = true), rule)
    if (rule.trigger == TriggerKind.TIME) {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val token = now.toLocalDate().toString() + ":" + rule.parameters.getValue("time")
        val match = event == "tick" && "%02d:%02d".format(java.util.Locale.ROOT, now.hour, now.minute) == rule.parameters["time"] && rule.lastToken != token
        return TriggerDecision(match, if (match) rule.copy(lastToken = token) else rule)
    }
    val eventName = when (rule.trigger) { TriggerKind.SCREEN_ON -> "screen_on"; TriggerKind.SCREEN_OFF -> "screen_off"; TriggerKind.BOOT -> "boot"; else -> null }
    if (eventName != null) return TriggerDecision(event == eventName, rule)
    val matches = when (rule.trigger) {
        TriggerKind.BATTERY_BELOW -> state.battery < rule.parameters.getValue("level").toInt()
        TriggerKind.CHARGING -> state.charging
        TriggerKind.DISCHARGING -> !state.charging
        TriggerKind.WIFI_CONNECTED -> state.wifi
        TriggerKind.SPECIFIC_WIFI -> state.wifi && state.ssid == rule.parameters["ssid"]
        TriggerKind.NETWORK_CONNECTED -> state.connected
        else -> false
    }
    return TriggerDecision(matches && !rule.lastMatched, rule.copy(lastMatched = matches))
}
class AutomationRepository(private val directory: File) {
    private val mutex = Mutex()
    private fun readAll(): List<AutomationRule> =
        if (!directory.exists()) emptyList() else directory.listFiles().orEmpty().filter { it.extension == "xml" }.map { file ->
            require(file.length() <= 65536) { throw Problem("err_size") }
            val text = file.readText(Charsets.UTF_8)
            require(!text.contains(Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE))) { throw Problem("err_xml") }
            val builder = DocumentBuilderFactory.newInstance().apply {
                runCatching { setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                isExpandEntityReferences = false
            }.newDocumentBuilder().apply { setEntityResolver { _, _ -> throw Problem("err_xml") } }
            val root = builder.parse(InputSource(StringReader(text))).documentElement
            require(root.tagName == "automation" && root.getAttribute("version") == "1") { throw Problem("err_xml") }
            val parameters = linkedMapOf<String, String>()
            val children=root.childNodes
            for (index in 0 until children.length) { val child = children.item(index) as? org.w3c.dom.Element ?: continue; require(child.tagName == "parameter" && child.getAttribute("key") !in parameters) { throw Problem("err_xml") }; parameters[child.getAttribute("key")] = child.textContent }
            AutomationRule(root.getAttribute("id"), root.getAttribute("name"), root.getAttribute("shortcutId"), TriggerKind.valueOf(root.getAttribute("trigger")), parameters,
                root.getAttribute("enabled").toBooleanStrict(), root.getAttribute("lastMatched").toBooleanStrict(), root.getAttribute("lastToken")).also { require(it.id == file.nameWithoutExtension) { throw Problem("err_xml") } }
        }

    suspend fun list(): List<AutomationRule> = withContext(Dispatchers.IO) { mutex.withLock { readAll() } }
    private fun write(rule: AutomationRule) {
        fun escape(value: String): String {
            require(value.codePoints().allMatch { it == 9 || it == 10 || it == 13 || it in 0x20..0xD7FF || it in 0xE000..0xFFFD || it in 0x10000..0x10FFFF }) { throw Problem("err_xml") }
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\n", "&#10;").replace("\r", "&#13;").replace("\t", "&#9;")
        }
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<automation version=\"1\" id=\"${rule.id}\" name=\"${escape(rule.name)}\" shortcutId=\"${rule.shortcutId}\" trigger=\"${rule.trigger}\" enabled=\"${rule.enabled}\" lastMatched=\"${rule.lastMatched}\" lastToken=\"${escape(rule.lastToken)}\">\n")
            rule.parameters.forEach { (key, value) -> append("<parameter key=\"${escape(key)}\">${escape(value)}</parameter>\n") }; append("</automation>\n")
        }.toByteArray(Charsets.UTF_8)
        require(xml.size <= 65536) { throw Problem("err_size") }; atomicWrite(File(directory, "${rule.id}.xml"), xml)
    }
    suspend fun save(rule: AutomationRule): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        require(File(directory, "${rule.id}.xml").exists() || readAll().size < 100) { throw Problem("err_size") }; write(rule)
    } }
    /** Compare and swap: a monitor tick must not undo a concurrent edit, disable or deletion. */
    suspend fun saveState(previous: AutomationRule, updated: AutomationRule): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val current = readAll().find { it.id == previous.id }
        if (current != previous) false else { write(updated); true }
    } }
    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) { mutex.withLock { require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))); java.nio.file.Files.deleteIfExists(File(directory, "$id.xml").toPath()); Unit } }
}

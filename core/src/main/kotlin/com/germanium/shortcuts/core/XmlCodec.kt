package com.germanium.shortcuts.core

import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory

class InvalidDocument(message: String, cause: Throwable? = null) : Exception(message, cause)

/** UTF-8 only, bounded, no DTD/entities; unknown action IDs and parameters round-trip unchanged. */
class XmlCodec {
    fun encode(shortcut: Shortcut): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        val version = if (allSteps(shortcut.steps).any { it.branches.isNotEmpty() }) 2 else 1
        append("<shortcut version=\"$version\" id=\"${escape(shortcut.id)}\" createdAt=\"${shortcut.createdAt}\" updatedAt=\"${shortcut.updatedAt}\" icon=\"${escape(shortcut.icon)}\" color=\"${escape(shortcut.color)}\" folder=\"${escape(shortcut.folderId)}\">\n")
        append("  <name>${escape(shortcut.name)}</name>\n  <steps>\n")
        fun writeSteps(steps: List<ActionStep>) {
            steps.forEach { step ->
                append("    <action id=\"${escape(step.id)}\" type=\"${escape(step.type)}\" enabled=\"${step.enabled}\">\n")
                step.parameters.toSortedMap().forEach { (key, value) ->
                    append("      <parameter key=\"${escape(key)}\">${escape(value)}</parameter>\n")
                }
                step.branches.forEach { branch ->
                    append("      <branch id=\"${escape(branch.id)}\" label=\"${escape(branch.label)}\">\n")
                    writeSteps(branch.steps)
                    append("      </branch>\n")
                }
                append("    </action>\n")
            }
        }
        writeSteps(shortcut.steps)
        append("  </steps>\n</shortcut>\n")
    }.toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) { throw Problem("err_xml") } }

    fun decode(bytes: ByteArray): Shortcut = guarded {
        val root = parse(bytes, "shortcut")
        readShortcut(root)
    }

    fun encodeBundle(shortcuts: List<Shortcut>): ByteArray {
        require(shortcuts.size <= 1000 && shortcuts.map { it.id }.distinct().size == shortcuts.size) { throw Problem("err_size") }
        val output = java.io.ByteArrayOutputStream()
        output.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<shortcuts version=\"1\">\n".toByteArray())
        for (shortcut in shortcuts) {
            val body = encode(shortcut).toString(Charsets.UTF_8).substringAfter("?>").trimStart().toByteArray(Charsets.UTF_8)
            require(output.size().toLong() + body.size + 32 <= MAX_BUNDLE_BYTES) { throw Problem("err_size") }
            output.write(body)
        }
        output.write("</shortcuts>\n".toByteArray())
        return output.toByteArray()
    }

    /** Accept a single shortcut or a bundle; decode every item before writing anything. */
    fun decodeImport(bytes: ByteArray): List<Shortcut> = guarded {
        val root = parse(bytes, "shortcuts", MAX_BUNDLE_BYTES, allowSingle = true)
        if (root.tagName == "shortcut") {
            require(bytes.size <= MAX_BYTES) { throw Problem("err_size") }
            listOf(readShortcut(root))
        } else {
            root.only("shortcut")
            val children = root.children(); require(children.size <= 1000) { throw Problem("err_size") }
            val result = children.map { readShortcut(it) }
            require(result.map { it.id }.distinct().size == result.size) { throw Problem("err_xml") }
            result
        }
    }

    private fun readShortcut(root: Element): Shortcut {
        require(root.required("version") in setOf("1", "2")) { throw Problem("err_xml") }
        root.only("name", "steps")
        var count = 0
        fun readSteps(parent: Element, depth: Int): List<ActionStep> {
            require(depth <= 16) { "Workflow nesting exceeds 16 levels" }
            return parent.children().map { action ->
                require(++count <= 1000 && action.tagName == "action") { "Invalid action count or element" }
                action.only("parameter", "branch")
                val params = action.children().filter { it.tagName == "parameter" }.map { it.required("key") to it.text() }
                require(params.map { it.first }.distinct().size == params.size) { "Duplicate parameter" }
                val branches = action.children().filter { it.tagName == "branch" }.map { branch ->
                    require(root.required("version") == "2") { "Branches require XML v2" }
                    branch.only("action")
                    ActionBranch(branch.required("id"), branch.getAttribute("label"), readSteps(branch, depth + 1))
                }
                ActionStep(action.required("id"), action.required("type"), params.toMap(), action.required("enabled").strictBoolean(), branches)
            }
        }
        return Shortcut(id = root.required("id"), name = root.one("name").text(),
            createdAt = root.required("createdAt").toLong(), updatedAt = root.required("updatedAt").toLong(),
            steps = readSteps(root.one("steps").also { it.only("action") }, 0),
            icon = if (root.hasAttribute("icon")) root.getAttribute("icon") else "layers",
            color = if (root.hasAttribute("color")) root.getAttribute("color") else "purple", folderId = root.getAttribute("folder"))
    }

    fun encode(settings: AppSettings): ByteArray =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<settings version=\"1\" mode=\"${settings.mode.name}\" theme=\"${settings.theme.name}\" angleUnit=\"${settings.angleUnit.name}\"/>\n".toByteArray(Charsets.UTF_8)

    fun decodeSettings(bytes: ByteArray): AppSettings = guarded {
        val root = parse(bytes, "settings")
        root.only()
        AppSettings(ExecutionMode.valueOf(root.required("mode")), root.getAttribute("theme").takeIf(String::isNotEmpty)?.let(ThemeMode::valueOf) ?: ThemeMode.SYSTEM, root.getAttribute("angleUnit").takeIf(String::isNotEmpty)?.let(AngleUnit::valueOf) ?: AngleUnit.DEGREES)
    }

    private fun parse(bytes: ByteArray, expected: String, limit: Int = MAX_BYTES, allowSingle: Boolean = false): Element {
        require(bytes.size <= limit) { throw Problem("err_xml") }
        val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require(!source.contains(Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE))) { throw Problem("err_xml") }
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("External entities are forbidden") }
        builder.setErrorHandler(object : DefaultHandler() {
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        })
        val root = builder.parse(InputSource(StringReader(source))).documentElement
        require((root.tagName == expected || (allowSingle && root.tagName == "shortcut")) && root.namespaceURI.isNullOrEmpty()) { throw Problem("err_xml") }
        require(root.required("version") in if (root.tagName == "shortcut") setOf("1", "2") else setOf("1")) { throw Problem("err_xml") }
        return root
    }

    private fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.only(vararg names: String) { require(children().all { it.tagName in names }) { throw Problem("err_xml") } }
    private fun Element.one(name: String): Element = children().filter { it.tagName == name }.single()
    private fun Element.required(name: String): String = getAttribute(name).also { require(it.isNotEmpty()) { throw Problem("err_xml") } }
    private fun Element.text(): String { require(children().isEmpty()) { throw Problem("err_xml") }; return textContent }
    private fun String.strictBoolean(): Boolean = when(this) { "true" -> true; "false" -> false; else -> throw Problem("err_xml") }
    private fun escape(value: String): String {
        require(value.codePoints().allMatch { it == 9 || it == 10 || it == 13 || it in 0x20..0xD7FF || it in 0xE000..0xFFFD || it in 0x10000..0x10FFFF }) { throw Problem("err_xml") }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;").replace("\r", "&#13;")
            .replace("\n", "&#10;").replace("\t", "&#9;")
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        throw InvalidDocument("Invalid XML", e)
    }
    companion object { const val MAX_BYTES = 1024 * 1024; const val MAX_BUNDLE_BYTES = 8 * 1024 * 1024 }
}

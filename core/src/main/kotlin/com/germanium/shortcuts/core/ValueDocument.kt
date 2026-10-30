package com.germanium.shortcuts.core

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

/** Tagged XML keeps lists/dictionaries typed while ordinary text files remain ordinary text. */
object ValueDocument {
    const val MAX_BYTES = 1024 * 1024
    const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- Shortcuts typed value v1 -->\n"
    fun encode(value: Value): String {
        if (value is Value.Text && !value.value.startsWith(HEADER)) return value.value
        fun escape(text: String): String {
            require(text.codePoints().allMatch { it == 9 || it == 10 || it == 13 || it in 32..0xD7FF || it in 0xE000..0xFFFD || it in 0x10000..0x10FFFF }) { throw Problem("err_xml") }
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#9;")
        }
        val output = buildString {
            append(HEADER).append("<shortcuts-value version=\"1\">")
            fun write(current: Value) {
                when (current) {
                    Value.Null -> append("<v type=\"null\"/>")
                    is Value.Text -> append("<v type=\"text\">${escape(current.value)}</v>")
                    is Value.RichText -> append("<v type=\"rich\">${escape(current.html)}</v>")
                    is Value.Number -> append("<v type=\"number\">${current.text()}</v>")
                    is Value.BooleanValue -> append("<v type=\"boolean\">${current.text()}</v>")
                    is Value.ListValue -> { append("<v type=\"list\">"); current.items.forEach(::write); append("</v>") }
                    is Value.Dictionary -> { append("<v type=\"dictionary\">"); current.entries.forEach { (key, item) -> append("<entry key=\"${escape(key)}\">"); write(item); append("</entry>") }; append("</v>") }
                }
            }
            write(value); append("</shortcuts-value>")
        }
        require(output.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { throw Problem("err_size") }; return output
    }
    fun decode(text: String): Value {
        if (!text.startsWith(HEADER)) return Value.Text(text)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && !text.contains(Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE))) { throw Problem("err_xml") }
        fun Element.children(): List<Element> { val nodes=childNodes;return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }
        val builder = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw Problem("err_xml") }
        val root = try { builder.parse(InputSource(StringReader(text.removePrefix(HEADER)))).documentElement } catch (_: Exception) { throw Problem("err_xml") }
        require(root.tagName == "shortcuts-value" && root.getAttribute("version") == "1") { throw Problem("err_xml") }
        fun read(element: Element, depth: Int): Value {
            require(depth <= 16 && element.tagName == "v") { throw Problem("err_xml") }
            val children = element.children(); require(children.size <= 256) { throw Problem("err_size") }
            return when (element.getAttribute("type")) {
                "null" -> { require(children.isEmpty() && element.textContent.isEmpty()) { throw Problem("err_xml") }; Value.Null }
                "list" -> Value.ListValue(children.map { read(it, depth + 1) })
                "dictionary" -> {
                    require(children.all { it.tagName == "entry" && it.hasAttribute("key") } && children.map { it.getAttribute("key") }.distinct().size == children.size) { throw Problem("err_xml") }
                    Value.Dictionary(children.associate { it.getAttribute("key") to read(it.children().singleOrNull() ?: throw Problem("err_xml"), depth + 1) })
                }
                "text", "number", "boolean", "rich" -> { require(children.isEmpty()) { throw Problem("err_xml") }; when (element.getAttribute("type")) {
                    "rich" -> RichTexts.fromHtml(element.textContent)
                    "text" -> Value.Text(element.textContent); "number" -> Value.Number(parseNumber(element.textContent)); else -> Value.BooleanValue(element.textContent.toBooleanStrictOrNull() ?: throw Problem("err_xml"))
                } }
                else -> throw Problem("err_xml")
            }
        }
        return read(root.children().singleOrNull() ?: throw Problem("err_xml"), 0)
    }
}

package com.germanium.shortcuts.core

import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

object RichTexts {
    private fun bounded(text: String) { require(text.length <= 131072) { throw Problem("err_size") } }
    fun fromHtml(html: String): Value.RichText {
        bounded(html)
        val safe = Jsoup.clean(html, Safelist.relaxed().removeTags("img").addAttributes(":all", "style"))
        val document = Jsoup.parseBodyFragment(safe)
        document.select("br").forEach { it.before("\n") }
        document.select("p,div,li,h1,h2,h3,h4,h5,h6,blockquote,pre,tr").forEach { it.appendText("\n") }
        val plain = document.body().wholeText().trimEnd()
        return Value.RichText(safe, plain)
    }
    fun fromMarkdown(markdown: String): Value.RichText {
        bounded(markdown)
        return fromHtml(HtmlRenderer.builder().build().render(Parser.builder().build().parse(markdown)))
    }
    fun html(value: Value): String = if(value is Value.RichText) value.html else "<p>" + org.jsoup.nodes.Entities.escape(value.text()).replace("\n", "<br>") + "</p>"
    fun markdown(value: Value): String = FlexmarkHtmlConverter.builder().build().convert(html(value)).also { bounded(it) }
}

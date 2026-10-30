package com.germanium.shortcuts.core

internal fun version105Actions(platform: PlatformActions): List<ActionHandler> = listOf(
    Handler("html_from_rich_text") { p,c -> Value.Text(RichTexts.html(c.resolve(p["source"] ?: "{{input}}"))) },
    Handler("markdown_from_rich_text") { p,c -> Value.Text(RichTexts.markdown(c.resolve(p["source"] ?: "{{input}}"))) },
    Handler("rich_text_from_html") { p,c -> RichTexts.fromHtml(c.text(p,"source","{{input}}")) },
    Handler("rich_text_from_markdown") { p,c -> RichTexts.fromMarkdown(c.text(p,"source","{{input}}")) },
    Handler("image_from_rich_text") { p,c -> platform.media105("image_from_rich_text",c.resolve(p["source"] ?: "{{input}}"),Value.Null,p.mapValues { c.render(it.value) }) },
    Handler("filter_images") { p,c -> platform.media105("filter_images",c.resolve(p["source"] ?: "{{input}}"),Value.Null,p.mapValues { c.render(it.value) }) },
    Handler("images_from_pdf") { p,c -> platform.media105("images_from_pdf",c.resolve(p["source"] ?: "{{input}}"),Value.Null,p.mapValues { c.render(it.value) }) },
    Handler("extract_text_from_image") { p,c -> platform.media105("extract_text_from_image",c.resolve(p["source"] ?: "{{input}}"),Value.Null,p.mapValues { c.render(it.value) }) },
    Handler("make_gif") { p,c -> platform.media105("make_gif",c.resolve(p["source"] ?: "{{input}}"),Value.Null,p.mapValues { c.render(it.value) }) },
    Handler("video_from_gif") { p,c -> platform.media105("video_from_gif",c.resolve(p["source"] ?: "{{input}}"),Value.Null,p.mapValues { c.render(it.value) }) },
    Handler("add_frames_to_gif") { p,c -> platform.media105("add_frames_to_gif",c.resolve(p["source"] ?: "{{input}}"),c.resolve(p["frames"] ?: ""),p.mapValues { c.render(it.value) }) },
    Handler("frames_from_image") { p,c -> platform.media105("frames_from_image",c.resolve(p["source"] ?: "{{input}}"),Value.Null,p.mapValues { c.render(it.value) }) },
    Handler("rename_file") { p,c -> platform.renameFile(c.text(p,"source","{{input}}"),FileNames.validate(c.text(p,"newName")),c.mode) },
)

object FileNames {
    fun validate(name: String): String {
        require(name.isNotBlank() && name.length <= 200 && name !in listOf(".","..") && name.none { it=='/'||it=='\\'||it==':'||it.code<32 }) { throw Problem("err_path") }
        return name
    }
}

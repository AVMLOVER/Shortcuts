package com.germanium.shortcuts

internal object Version105Catalog {
    private val source=ActionField("source","source","{{input}}",true)
    private val destination=listOf(ActionField("folder","folder","Images"),ActionField("filename","filename"))
    private val dimensions=listOf(ActionField("width","image_width","512"),ActionField("height","image_height","512"))
    private val frameOptions=listOf(ActionField("frameDelay","gif_frame_delay","100"),ActionField("loopCount","gif_loop_count","0"),ActionField("maxFrames","media_max_frames","120"))
    val all=listOf(
        ActionDefinition("html_from_rich_text","text","HTML",listOf(source)),
        ActionDefinition("markdown_from_rich_text","text","MD",listOf(source)),
        ActionDefinition("rich_text_from_html","text","Aa",listOf(source)),
        ActionDefinition("rich_text_from_markdown","text","Aa",listOf(source)),
        ActionDefinition("image_from_rich_text","images","Aa▧",listOf(source,ActionField("width","image_width","1024"),ActionField("fontSize","font_size","32"),ActionField("padding","padding","24"))+destination),
        ActionDefinition("filter_images","images","▧?",listOf(source,
            ActionField("property","filter_property","width",choices=listOf("width" to "image_width","height" to "image_height","name" to "filename","mimeType" to "image_format","size" to "size","orientation" to "image_orientation")),
            ActionField("operator","operator","greater",choices=listOf("equals","not_equals","greater","greater_equal","less","less_equal","contains","starts_with","ends_with").map{it to it}),
            ActionField("compare","right","0"),ActionField("sort","sort_order","original",choices=listOf("original" to "original_order","name" to "filename","width" to "image_width","height" to "image_height","size" to "size")),
            ActionField("descending","descending","false",choices=listOf("false" to "not_allowed","true" to "allowed")),ActionField("limit","application_list_limit","256"))),
        ActionDefinition("images_from_pdf","images","PDF▧",listOf(source,ActionField("pages","pdf_pages","all"),ActionField("width","image_width","1440"),ActionField("folder","folder","PDF Images"))),
        ActionDefinition("extract_text_from_image","images","Aa↓",listOf(source,ActionField("language","ocr_script","chinese",choices=listOf("chinese" to "ocr_chinese","latin" to "ocr_latin","japanese" to "ocr_japanese")))),
        ActionDefinition("make_gif","images","GIF",listOf(source)+dimensions+frameOptions+listOf(ActionField("folder","folder","GIFs"),ActionField("filename","filename"))),
        ActionDefinition("video_from_gif","images","GIF▶",listOf(source,ActionField("folder","folder","Videos"),ActionField("filename","filename"))),
        ActionDefinition("add_frames_to_gif","images","GIF+",listOf(source,ActionField("frames","gif_new_frames","",true))+dimensions+frameOptions+listOf(ActionField("folder","folder","GIFs"),ActionField("filename","filename"))),
        ActionDefinition("frames_from_image","images","▧↓",listOf(source,ActionField("maxFrames","media_max_frames","120"),ActionField("folder","folder","Frames"))),
        ActionDefinition("rename_file","files","▱✎",listOf(source,ActionField("newName","new_filename"))),
    )
}

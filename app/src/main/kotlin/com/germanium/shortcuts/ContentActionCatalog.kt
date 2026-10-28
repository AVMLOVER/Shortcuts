package com.germanium.shortcuts

import com.germanium.shortcuts.core.ContentHash
import com.germanium.shortcuts.core.ContentNumbers

internal object ContentActionCatalog {
    private val source = ActionField("source", "source", "{{input}}", true)
    private val boolean = listOf("false" to "not_allowed", "true" to "allowed")
    private val interactive = ActionField("interactive", "edit_when_run", "false", choices = boolean)
    private val destination = listOf(ActionField("folder", "folder", "Images"), ActionField("filename", "filename"))
    val all = listOf(
        ActionDefinition("repeat_until", "control", "↻?", emptyList()),
        ActionDefinition("factor_number", "math", "∏", listOf(source, ActionField("factorType", "factor_type", "prime_factors", choices = listOf("prime_factors" to "prime_factors", "divisors" to "all_divisors", "is_prime" to "is_prime")), ActionField("powers", "factor_powers", "false", choices = boolean))),
        ActionDefinition("resize_image", "images", "↔", listOf(source, ActionField("width", "image_width", "1024"), ActionField("height", "image_height", "0"), ActionField("keepAspect", "keep_aspect", "true", choices = boolean), interactive) + destination),
        ActionDefinition("compress_image", "images", "▧−", listOf(source, ActionField("quality", "image_quality", "80"), ActionField("imageFormat", "image_format", "jpeg", choices = listOf("jpeg" to "format_jpeg", "webp" to "format_webp")), interactive) + destination),
        ActionDefinition("text_image", "images", "T▧", listOf(source, ActionField("width", "image_width", "1024"), ActionField("fontSize", "font_size", "32"), ActionField("padding", "image_padding", "24"), ActionField("alignment", "text_alignment", "start", choices = listOf("start" to "align_start", "center" to "align_center", "end" to "align_end")), ActionField("foregroundColor", "text_color"), ActionField("backgroundColor", "background_color"), ActionField("transparentBackground", "transparent_background", "false", choices = boolean)) + destination),
        ActionDefinition("make_pdf", "files", "PDF", listOf(source, ActionField("pdfInput", "pdf_input", "auto", choices = listOf("auto" to "auto_type", "text" to "text_type", "images" to "image_type")), ActionField("pageSize", "page_size", "a4", choices = listOf("a4" to "paper_a4", "letter" to "paper_letter")), ActionField("fontSize", "font_size", "12"), ActionField("margin", "page_margin", "36"), ActionField("saveDirectoryUri", "default_save_directory"), ActionField("filename", "filename"))),
        ActionDefinition("share_input", "basic", "⇥", listOf(ActionField("contentType", "content_type", "any", choices = listOf("any" to "any_content", "text" to "text_type", "images" to "image_type", "urls" to "url", "files" to "file_type")))),
        ActionDefinition("comment", "basic", "//", listOf(ActionField("note", "comment_text", multiline = true))),
        ActionDefinition("count", "basic", "#", listOf(source, ActionField("countType", "count_type", "items", choices = listOf("items", "characters", "words", "sentences", "lines").map { it to "count_$it" }))),
        ActionDefinition("statistics", "math", "Σ", listOf(source, ActionField("statistic", "statistic", "average", choices = ContentNumbers.statistics.map { it to "stat_$it" }))),
        ActionDefinition("format_number", "math", "1.00", listOf(source, ActionField("digits", "format_digits", "2"), ActionField("grouping", "number_grouping", "true", choices = boolean), ActionField("locale", "number_locale"))),
        ActionDefinition("battery_level", "device", "▰", emptyList()),
        ActionDefinition("share", "system", "↥", listOf(source)),
        ActionDefinition("wait_return", "control", "↩", emptyList()),
        ActionDefinition("get_text_from_input", "text", "T↓", listOf(source)),
        ActionDefinition("get_images_from_input", "images", "▧↓", listOf(source)),
        ActionDefinition("get_urls_from_input", "web", "↗↓", listOf(source)),
        ActionDefinition("get_files_from_input", "files", "▱↓", listOf(source)),
        ActionDefinition("get_numbers_from_input", "math", "123↓", listOf(source)),
        ActionDefinition("mask_image", "images", "◐", listOf(source,
            ActionField("maskShape", "mask_shape", "ellipse", choices = listOf("ellipse", "circle", "rounded", "rectangle", "custom").map { it to "mask_$it" }),
            ActionField("secondary", "secondary_image"), ActionField("maskMode", "mask_mode", "alpha", choices = listOf("alpha" to "mask_alpha", "luminance" to "mask_luminance")),
            ActionField("radius", "mask_radius", "25"), ActionField("invert", "invert_mask", "false", choices = boolean), interactive) + destination),
        ActionDefinition("overlay_image", "images", "▧+", listOf(source, ActionField("secondary", "secondary_image"), ActionField("scale", "image_scale", "50"), ActionField("opacity", "image_opacity", "100"),
            ActionField("rotation", "image_rotation", "0"), ActionField("offsetX", "image_offset_x", "0"), ActionField("offsetY", "image_offset_y", "0"), interactive) + destination),
        ActionDefinition("flip_image", "images", "⇋", listOf(source, ActionField("flipDirection", "flip_direction", "horizontal", choices = listOf("horizontal" to "direction_horizontal", "vertical" to "direction_vertical", "both" to "direction_both")), interactive) + destination),
        ActionDefinition("combine_images", "images", "▧▧", listOf(source, ActionField("direction", "combine_direction", "horizontal", choices = listOf("horizontal" to "direction_horizontal", "vertical" to "direction_vertical", "grid" to "direction_grid")),
            ActionField("spacing", "image_spacing", "0"), ActionField("columns", "grid_columns", "2"), ActionField("matchDimensions", "match_dimensions", "true", choices = boolean), interactive) + destination),
    )
    val hashFields = listOf(source, ActionField("inputType", "input_type", "text", choices = listOf("text" to "text_type", "image" to "image_type", "file" to "file_type")), ActionField("algorithm", "algorithm", "SHA-256", choices = ContentHash.algorithms.map { it to it }))
}

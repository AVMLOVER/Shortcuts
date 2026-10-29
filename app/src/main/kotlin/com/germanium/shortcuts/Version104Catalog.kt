package com.germanium.shortcuts

internal object Version104Catalog {
    private val source = ActionField("source", "source", "{{input}}", true)
    private val yesNo = listOf("false" to "not_allowed", "true" to "allowed")
    private val units = listOf("seconds","minutes","hours","days","weeks","months","years").map { it to "date_unit_$it" }
    private val imageDestination = listOf(ActionField("folder","folder","Images"),ActionField("filename","filename"))
    private val interactive = ActionField("interactive","edit_when_run","false",choices = yesNo)
    val all = listOf(
        ActionDefinition("url","web","↗",listOf(ActionField("url","url","https://"))),
        ActionDefinition("shutdown","device","⏻",emptyList()),
        ActionDefinition("reboot","device","↻",listOf(ActionField("target","reboot_target","system",choices = com.germanium.shortcuts.core.PowerTargets.all.map { it to "reboot_target_$it" }))),
        ActionDefinition("write_partition","device","!",listOf(source,ActionField("partition","partition_target"))),
        ActionDefinition("get_dictionary_from_input","dictionary","{}↓",listOf(source)),
        ActionDefinition("encode_json","dictionary","{}T",listOf(source)),
        ActionDefinition("adjust_date","dates","◷+",listOf(source,ActionField("amount","date_amount","1"),ActionField("unit","unit","days",choices=units),ActionField("zone","zone"))),
        ActionDefinition("date_difference","dates","◷−",listOf(ActionField("start","date_start","{{input}}"),ActionField("end","date_end_now"),ActionField("unit","unit","seconds",choices=listOf("milliseconds" to "milliseconds")+units),ActionField("zone","zone"),ActionField("absolute","absolute_difference","false",choices=yesNo))),
        ActionDefinition("format_filesize","files","B",listOf(source,ActionField("digits","format_digits","2"),ActionField("binary","binary_units","true",choices=yesNo))),
        ActionDefinition("rotate_image","images","↻",listOf(source,ActionField("rotation","image_rotation","90"),interactive)+imageDestination),
        ActionDefinition("convert_image","images","▧⇄",listOf(source,ActionField("imageFormat","image_format","png",choices=listOf("png" to "format_png","jpeg" to "format_jpeg","webp" to "format_webp")),ActionField("quality","image_quality","90"),interactive)+imageDestination),
        ActionDefinition("make_archive","files","ZIP",listOf(source,ActionField("folder","folder","Archives"),ActionField("filename","filename","archive.zip"))),
        ActionDefinition("extract_archive","files","ZIP↓",listOf(source,ActionField("folder","folder","Extracted"))),
        ActionDefinition("generate_qr","images","▦",listOf(source,ActionField("size","qr_size","512"),ActionField("correction","qr_correction","M",choices=listOf("L","M","Q","H").map { it to it }))),
        ActionDefinition("read_barcode","images","▦↓",listOf(source)),
        ActionDefinition("speak_text","device","♪",listOf(source,ActionField("language","speech_language"),ActionField("rate","speech_rate","1"),ActionField("pitch","speech_pitch","1"))),
        ActionDefinition("get_volume","device","♪?",emptyList()),
        ActionDefinition("set_volume","device","♪=",listOf(ActionField("percent","volume_percent","50"))),
        ActionDefinition("set_flashlight","device","☀",listOf(ActionField("enabled","flashlight_enabled","true",choices=yesNo))),
    )
}

package com.germanium.shortcuts

import android.content.Context
import com.germanium.shortcuts.core.*

data class ActionField(val key: String, val label: String, val initial: String = "", val multiline: Boolean = false, val choices: List<Pair<String, String>> = emptyList())
data class ActionDefinition(val type: String, val group: String, val symbol: String, val fields: List<ActionField>) {
    val title get() = "action_$type"
    fun create(context: Context): ActionStep {
        val branches = when (type) {
            "if" -> listOf(ActionBranch("then"), ActionBranch("else"))
            "menu" -> listOf(ActionBranch(label = context.tr("menu_item") + " 1"), ActionBranch(label = context.tr("menu_item") + " 2"))
            "repeat", "repeat_each" -> listOf(ActionBranch("body"))
            else -> emptyList()
        }
        return ActionStep(type = type, parameters = fields.associate { it.key to when { type == "read_calendar" && it.key == "date" -> java.time.LocalDate.now().toString(); type == "current_date" && it.key == "format" -> defaultCurrentDateTimePattern(context.resources.configuration.locales[0]); else -> it.initial } }, branches = branches)
    }
}
object ActionCatalog {
    val valueTypes = listOf("auto" to "auto_type", "text" to "text_type", "number" to "number_type", "boolean" to "boolean_type")
    val extraTypes = listOf("string" to "text_type", "int" to "integer_type", "long" to "long_type", "double" to "decimal_type", "boolean" to "boolean_type")
    private val boolean = listOf("false" to "not_allowed", "true" to "allowed")
    private val value = ActionField("value", "value", "{{input}}", true)
    private val source = ActionField("source", "source", "{{input}}", true)
    private val dictionary = ActionField("dictionary", "dictionary_source", "{{input}}")
    private val key = ActionField("key", "key", "name")
    private val valueType = ActionField("valueType", "value_type", "auto", choices = valueTypes)
    val all = listOf(
        ActionDefinition("text", "basic", "T", listOf(ActionField("value", "text_type", multiline = true))),
        ActionDefinition("number", "math", "123", listOf(ActionField("value", "number_type", "0"))),
        ActionDefinition("current_location", "device", "◎", emptyList()),
        ActionDefinition("read_calendar", "dates", "▣↓", listOf(ActionField("date", "date_type", java.time.LocalDate.now().toString()), ActionField("zone", "zone"))),
        ActionDefinition("screenshot", "device", "▣", listOf(ActionField("filename", "filename", "screenshot.png"))),
        ActionDefinition("append_text", "text", "T+", listOf(ActionField("target", "append_target", "{{input}}", true), ActionField("value", "value", multiline = true))),
        ActionDefinition("delay", "basic", "◷", listOf(ActionField("milliseconds", "duration", "500"), ActionField("unit", "unit", "milliseconds", choices = listOf("milliseconds" to "milliseconds", "seconds" to "seconds")))),
        ActionDefinition("show_result", "basic", "▤", listOf(ActionField("title", "title", "Shortcuts"), ActionField("body", "body", "{{input}}", true), ActionField("allowCancel", "allow_cancel", "false", choices = boolean))),
        ActionDefinition("shortcut_count", "basic", "#", emptyList()),
        ActionDefinition("choose_file", "files", "▱", emptyList()),
        ActionDefinition("get_image", "images", "▧", listOf(ActionField("source", "source"))),
        ActionDefinition("take_photo", "images", "▧", emptyList()),
        ActionDefinition("image_info", "images", "ⓘ", listOf(source)),
        ActionDefinition("crop_image", "images", "▧", listOf(source, ActionField("x", "pixel_x", "0"), ActionField("y", "pixel_y", "0"), ActionField("width", "image_width", "100"), ActionField("height", "image_height", "100"), ActionField("folder", "folder", "Images"), ActionField("filename", "filename", "crop.png"))),
        ActionDefinition("vibrate", "device", "≋", listOf(ActionField("duration", "duration", "200"), ActionField("unit", "unit", "milliseconds", choices = listOf("milliseconds" to "milliseconds", "seconds" to "seconds")), ActionField("amplitude", "amplitude", "128"))),
        ActionDefinition("output", "basic", "↗", listOf(value)),
        ActionDefinition("quick_look", "basic", "◉", listOf(value)),
        ActionDefinition("create_variable", "variables", "x", listOf(ActionField("name", "variable_name", "value"), valueType, value)),
        ActionDefinition("get_variable", "variables", "x↓", listOf(ActionField("name", "variable_name", "value"))),
        ActionDefinition("append_variable", "variables", "x+", listOf(ActionField("name", "variable_name", "value"), value)),
        ActionDefinition("ask_input", "basic", "?…", listOf(ActionField("title", "title"), ActionField("prompt", "prompt"), ActionField("initial", "initial"), ActionField("inputType", "input_type", "text", choices = listOf("text" to "text_type", "number" to "number_type", "date" to "date_type")))),
        ActionDefinition("calculate", "math", "=", listOf(ActionField("left", "left", "{{input}}"), ActionField("operation", "math_operator", "+", choices = listOf("+", "-", "*", "/", "%", "^", "mod", "sin", "cos", "tan", "asin", "acos", "atan", "abs", "sqrt", "cbrt", "root", "round", "min", "max", "gcd", "lcm").map { it to if (it == "round") "action_round_number" else it }), ActionField("right", "right", "1"), ActionField("rounding", "rounding_mode", "nearest", choices = listOf("nearest" to "round_nearest", "up" to "round_up", "down" to "round_down")), ActionField("digits", "rounding_digits", "0"))),
        ActionDefinition("calculate_expression", "math", "ƒ", listOf(ActionField("expression", "expression", "", true))),
        ActionDefinition("round_number", "math", "≈", listOf(source, ActionField("rounding", "rounding_mode", "nearest", choices = listOf("nearest" to "round_nearest", "up" to "round_up", "down" to "round_down")), ActionField("digits", "rounding_digits", "0"))),
        ActionDefinition("dictionary", "dictionary", "{}", emptyList()),
        ActionDefinition("get_dictionary_value", "dictionary", "{}↓", listOf(dictionary, key)),
        ActionDefinition("set_dictionary_value", "dictionary", "{}+", listOf(dictionary, key, valueType, value)),
        ActionDefinition("replace_text", "text", "T↔", listOf(source, ActionField("find", "find"), ActionField("replacement", "replacement", multiline = true), ActionField("ignoreCase", "ignore_case", "false", choices = boolean))),
        ActionDefinition("regex_replace", "text", ".*", listOf(source, ActionField("pattern", "pattern"), ActionField("replacement", "replacement", multiline = true), ActionField("groups", "capture_groups", "false", choices = boolean), ActionField("ignoreCase", "ignore_case", "false", choices = boolean))),
        ActionDefinition("match_text", "text", ".*?", listOf(source, ActionField("pattern", "pattern"), ActionField("ignoreCase", "ignore_case", "false", choices = boolean))),
        ActionDefinition("text_case", "text", "Aa", listOf(source, ActionField("case", "case", "upper", choices = listOf("upper" to "upper", "lower" to "lower")))),
        ActionDefinition("trim_text", "text", "T−", listOf(source)),
        ActionDefinition("count_text", "text", "#", listOf(source)),
        ActionDefinition("split_text", "text", "T/", listOf(source, ActionField("separator", "separator", ","))),
        ActionDefinition("join_text", "text", "T&", listOf(source, ActionField("separator", "separator", ","))),
        ActionDefinition("if", "control", "?", listOf(ActionField("left", "left", "{{input}}"), ActionField("operator", "operator", "equals", choices = ShortcutEngine.CONDITIONS.map { it to it }), ActionField("right", "right"))),
        ActionDefinition("menu", "control", "☰", listOf(ActionField("title", "title", contextFreeMenuTitle()))),
        ActionDefinition("repeat", "control", "↻", listOf(ActionField("count", "count", "2"))),
        ActionDefinition("stop", "control", "■", listOf(value)),
        ActionDefinition("notification", "system", "♧", listOf(ActionField("title", "title", "Shortcuts"), ActionField("body", "body", "{{input}}", true))),
        ActionDefinition("calendar", "system", "▣", listOf(ActionField("title", "title"), ActionField("start", "start"), ActionField("end", "end"), ActionField("description", "description", multiline = true), ActionField("location", "location"), ActionField("calendarId", "calendar_id"), ActionField("reminderMinutes", "reminder"))),
        ActionDefinition("install_apk", "apps", "↓", emptyList()),
        ActionDefinition("shell", "system", ">_", listOf(ActionField("command", "command", "id", true), ActionField("timeoutSeconds", "timeout_seconds", "30"))),
        ActionDefinition("intent", "apps", "↗", listOf(ActionField("action", "action_uri", "android.intent.action.MAIN"), ActionField("data", "data_uri"), ActionField("mimeType", "mime_type"), ActionField("packageName", "package_name"), ActionField("className", "class_name"), ActionField("categories", "categories", multiline = true))),
    ) + listOf(
        ActionDefinition("list", "lists", "≡", emptyList()),
        ActionDefinition("get_list_item", "lists", "≡↓", listOf(source, ActionField("selection", "selection", "index", choices = listOf("first", "last", "random", "index", "range").map { it to it }), ActionField("index", "index", "1"), ActionField("endIndex", "end_index", "1"))),
        ActionDefinition("choose_from_list", "lists", "☑", listOf(source, ActionField("title", "title", "Shortcuts"), ActionField("multiple", "multiple", "false", choices = boolean))),
        ActionDefinition("sort_list", "lists", "↑↓", listOf(source, ActionField("numeric", "numeric", "false", choices = boolean), ActionField("descending", "descending", "false", choices = boolean))),
        ActionDefinition("count_items", "lists", "#", listOf(source)),
        ActionDefinition("reverse_list", "lists", "⇅", listOf(source)),
        ActionDefinition("dictionary_keys", "dictionary", "{}#", listOf(source)),
        ActionDefinition("repeat_each", "control", "↻≡", listOf(source)),
        ActionDefinition("open_app", "apps", "▣", emptyList()),
        ActionDefinition("reminder", "system", "♧", listOf(ActionField("body", "body", "{{input}}", true))),
        ActionDefinition("current_date", "dates", "◷", listOf(ActionField("format", "format", defaultCurrentDateTimePattern()), ActionField("zone", "zone"))),
        ActionDefinition("format_date", "dates", "◷T", listOf(source, ActionField("format", "format", "yyyy-MM-dd HH:mm:ss"), ActionField("zone", "zone"))),
        ActionDefinition("random_number", "math", "⚄", listOf(ActionField("minimum", "minimum", "1"), ActionField("maximum", "maximum", "100"))),
        ActionDefinition("generate_uuid", "basic", "ID", listOf(ActionField("seed", "seed", "{{input}}"))),
        ActionDefinition("hash_text", "encoding", "#", listOf(source, ActionField("algorithm", "algorithm", "SHA-256", choices = listOf("SHA-256", "SHA-1", "MD5").map { it to it }))),
        ActionDefinition("get_url", "web", "↓", listOf(ActionField("url", "url", "https://"))),
        ActionDefinition("get_html", "web", "</>", listOf(ActionField("url", "url", "https://"))),
        ActionDefinition("view_html", "web", "</>", listOf(ActionField("html", "html_source", "{{input}}", true))),
        ActionDefinition("open_url", "web", "↗", listOf(ActionField("url", "url", "https://"))),
        ActionDefinition("get_clipboard", "device", "▤↓", emptyList()),
        ActionDefinition("set_clipboard", "device", "▤↑", listOf(value)),
        ActionDefinition("device_info", "device", "▣?", emptyList()),
    ) + listOf("encode_base64", "decode_base64", "encode_url", "decode_url").map { ActionDefinition(it, "encoding", "⇄", listOf(source) + if (it == "encode_base64") listOf(ActionField("inputType", "input_type", "text", choices = listOf("text" to "text_type", "image" to "image_type", "file" to "file_type")), ActionField("lineLength", "line_length", "0", choices = listOf("0" to "no_wrap", "64" to "wrap_64", "76" to "wrap_76"))) else emptyList()) } +
        listOf("create_folder", "create_file", "read_file", "write_file", "append_file", "list_files", "delete_file", "file_info", "file_metadata").map { operation ->
            ActionDefinition(operation, "files", "▱", listOf(ActionField("external", "external_path", "false", choices = boolean), ActionField("folder", "folder", "Documents")) +
                (if (operation in listOf("create_folder", "list_files")) emptyList() else listOf(ActionField("filename", "filename", "note.txt"))) +
                (if (operation in listOf("write_file", "append_file")) listOf(ActionField("content", "content", "{{input}}", true)) else emptyList()))
        }
    private fun contextFreeMenuTitle() = ""
    private val byType = all.associateBy { it.type }
    fun find(type: String) = byType[if (type == "set_variable") "create_variable" else type]
    fun forStep(step: ActionStep): ActionDefinition? = if (step.type == "calculate" && "expression" in step.parameters) byType["calculate_expression"] else find(step.type)
}

package com.germanium.shortcuts.core

object WorkflowExamples {
    fun dictionaryCalculation() = Shortcut(name = "Calculate an order total", steps = listOf(
        ActionStep(type = "dictionary", parameters = mapOf("entries" to "text|item|Coffee\nnumber|price|18.5\nnumber|quantity|2", "resultVariable" to "order")),
        ActionStep(type = "get_dictionary_value", parameters = mapOf("dictionary" to "{{order}}", "key" to "price", "resultVariable" to "price")),
        ActionStep(type = "get_dictionary_value", parameters = mapOf("dictionary" to "{{order}}", "key" to "quantity", "resultVariable" to "quantity")),
        ActionStep(type = "calculate", parameters = mapOf("expression" to "price * quantity", "resultVariable" to "total")),
        ActionStep(type = "text", parameters = mapOf("value" to "Total {{total}}")),
        ActionStep(type = "output"),
    ))
}

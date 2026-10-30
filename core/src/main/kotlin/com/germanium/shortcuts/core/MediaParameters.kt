package com.germanium.shortcuts.core

object MediaParameters {
    fun pages(selection:String,count:Int):List<Int> {
        require(count>0) { throw Problem("err_pdf") }
        if(selection.isBlank()||selection=="all") { require(count<=100) { throw Problem("err_size") };return (0 until count).toList() }
        val result=linkedSetOf<Int>()
        for(part in selection.split(',')) {
            val range=part.trim().split('-').map { it.trim().toIntOrNull() ?: throw Problem("err_pdf_pages") }
            require(range.size in 1..2) { throw Problem("err_pdf_pages") }
            val start=range.first();val end=range.last()
            require(start in 1..count&&end in start..count&&end-start<100) { throw Problem("err_pdf_pages") }
            for(page in start..end) {result+=page-1;require(result.size<=100) { throw Problem("err_size") }}
        }
        return result.toList()
    }
    fun matches(left:Value,operator:String,right:String):Boolean = when(operator) {
        "equals" -> if(left is Value.Number)left.number().compareTo(parseNumber(right))==0 else left.text().equals(right,true)
        "not_equals" -> !matches(left,"equals",right)
        "greater" -> left.number()>parseNumber(right)
        "greater_equal" -> left.number()>=parseNumber(right)
        "less" -> left.number()<parseNumber(right)
        "less_equal" -> left.number()<=parseNumber(right)
        "contains" -> left.text().contains(right,true)
        "starts_with" -> left.text().startsWith(right,true)
        "ends_with" -> left.text().endsWith(right,true)
        else -> throw Problem("err_invalid_data")
    }
}

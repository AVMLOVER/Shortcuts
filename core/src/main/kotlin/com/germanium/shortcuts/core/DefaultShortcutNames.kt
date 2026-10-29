package com.germanium.shortcuts.core

/** Reserve defaults across simultaneously open editor windows without changing workflow XML. */
class DefaultShortcutNames {
    private val reserved=mutableMapOf<String,String>()
    @Synchronized fun reserve(id:String,base:String,existing:Collection<String>):String {
        val occupied=existing.map(String::trim).toHashSet()
        reserved.filterKeys{it!=id}.values.forEach(occupied::add)
        val current=reserved[id]
        val name=if(current!=null&&current !in occupied)current else next(base,occupied)
        reserved[id]=name;return name
    }
    @Synchronized fun release(id:String){reserved.remove(id)}
    companion object {
        fun next(base:String,existing:Collection<String>):String {
            val prefix=base.trim();require(prefix.isNotBlank()&&prefix.length<=180){throw Problem("err_invalid_data")}
            val occupied=existing.map(String::trim).toHashSet()
            if(prefix !in occupied)return prefix
            var suffix=1
            while("$prefix$suffix" in occupied)suffix++
            return "$prefix$suffix"
        }
    }
}

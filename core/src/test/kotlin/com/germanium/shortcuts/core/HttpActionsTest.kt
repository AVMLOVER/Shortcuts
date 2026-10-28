package com.germanium.shortcuts.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HttpActionsTest {
    private class Backend : PlatformActions {
        val requests=mutableListOf<HttpRequestSpec>()
        var response=HttpResponseSpec("https://example.org/final",200, mapOf("x-test" to listOf("a","b")),"ok".toByteArray())
        var selected:String?="PATCH"
        override suspend fun requestHttp(request:HttpRequestSpec):HttpResponseSpec { requests+=request;return response }
        override suspend fun shell(command:String,mode:ExecutionMode,timeoutMillis:Long):ShellResult=throw Problem("err_backend")
        override suspend fun launchIntent(spec:IntentSpec) { }
        override suspend fun chooseMenu(title:String,items:List<Pair<String,String>>)=selected
        override suspend fun readBinary(source:String)=byteArrayOf(1,2,3)
    }
    private val context=RunContext(ExecutionMode.STANDARD,Value.Text("input"),VariableScope())
    @Test fun allFiveMethodsPassHeadersAndOnlyBodyMethodsSendJson() = runTest {
        for (method in HttpActions.methods) {
            val backend=Backend();val p=writeEntrySpecs(mapOf("url" to "https://example.org", "method" to method),"header","header",listOf(EntrySpec("text","X-Test","{{input}}")))
            assertEquals("ok",HttpActions.request(p,context,backend,"get_url").text())
            val request=backend.requests.single();assertEquals(method,request.method);assertEquals("input",request.headers["X-Test"])
            assertEquals(if(method in listOf("POST","PUT","PATCH")) "{}" else null,request.body?.toString(Charsets.UTF_8))
        }
    }
    @Test fun jsonFormAndFileBodiesKeepTheirValuesAndXmlParameters() = runTest {
        val fields=listOf(EntrySpec("number","count","2"),EntrySpec("text","line","a\nb"))
        val p=writeEntrySpecs(mapOf("url" to "https://example.org", "method" to "POST"),"bodyEntry","bodyEntry",fields)
        val backend=Backend();HttpActions.request(p,context,backend,"get_url")
        assertEquals("{\"count\":2,\"line\":\"a\\nb\"}",backend.requests.last().body!!.toString(Charsets.UTF_8))
        HttpActions.request(p+mapOf("bodyType" to "form"),context,backend,"get_url")
        assertEquals("count=2&line=a%0Ab",backend.requests.last().body!!.toString(Charsets.UTF_8))
        HttpActions.request(p+mapOf("bodyType" to "file","bodyFile" to "file"),context,backend,"get_url")
        assertArrayEquals(byteArrayOf(1,2,3),backend.requests.last().body)
        HttpActions.request(p+mapOf("bodyType" to "text","contentType" to "","body" to "plain"),context,backend,"get_url")
        assertEquals("text/plain; charset=utf-8",backend.requests.last().headers["Content-Type"])
        val source=Shortcut(name="HTTP",steps=listOf(ActionStep(type="get_url",parameters=p)))
        assertEquals(source,XmlCodec().decode(XmlCodec().encode(source)))
    }
    @Test fun headersAndExpansionUseHeadAndReturnTypedOutputs() = runTest {
        val backend=Backend();val p=mapOf("url" to "https://example.org")
        assertEquals("https://example.org/final",HttpActions.request(p,context,backend,"expand_url").text())
        val headers=HttpActions.request(p,context,backend,"get_url_headers") as Value.Dictionary
        assertEquals(listOf("a","b"),(headers.entries.getValue("x-test") as Value.ListValue).items.map{it.text()})
        assertTrue(backend.requests.all{it.method=="HEAD"&&!it.readBody})
    }
    @Test fun methodsCanBeAskedOrTakenFromVariablesAndBadHeadersAreRejected() = runTest {
        val backend=Backend();val p=mapOf("url" to "https://example.org")
        HttpActions.request(p+mapOf("method" to "ask"),context,backend,"get_url");assertEquals("PATCH",backend.requests.last().method)
        HttpActions.request(p+mapOf("method" to "variable","customMethod" to "put"),context,backend,"get_url");assertEquals("PUT",backend.requests.last().method)
        assertThrows(Problem::class.java){HttpActions.validateHeaders(mapOf("X-Test" to "ok\r\nInjected: true"))}
        assertThrows(Problem::class.java){HttpActions.validateUrl("file:///private")}
    }
    @Test fun componentsAreDecodedAndPortsRemainNumbers() {
        val all=HttpActions.component("https://user:pass@example.org:8443/a%20b?q=a%20b#top","all") as Value.Dictionary
        assertEquals("https",all.entries.getValue("scheme").text());assertEquals("user",all.entries.getValue("user").text())
        assertEquals(Value.Number(8443.toBigDecimal()),all.entries["port"]);assertEquals("/a b",all.entries.getValue("path").text());assertEquals("q=a%20b",all.entries.getValue("query").text())
        assertEquals("top",all.entries.getValue("fragment").text())
    }
    @Test fun inputExtractionFindsMultipleLinksAndSupportsAppSchemes() {
        assertEquals(listOf("https://example.org","shortcuts://run?id=1"),ContentInput.urls("See https://example.org and shortcuts://run?id=1").map{it.text()})
    }
}

package com.germanium.shortcuts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.InetAddress
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Network writes stay inside a loopback fixture, without changing any external API. */
@RunWith(AndroidJUnit4::class)
class HttpDeviceTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShortcutsApplication
    private data class Request(val method:String,val path:String,val headers:Map<String,String>,val body:ByteArray)
    private data class Reply(val code:Int=200,val headers:List<Pair<String,String>> = emptyList(),val body:String="ok")
    private class LocalServer(val reply:(Request)->Reply) : Closeable {
        private val socket=ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))
        val url="http://127.0.0.1:${socket.localPort}"
        val requests=CopyOnWriteArrayList<Request>()
        private fun InputStream.line():String {
            val text=StringBuilder()
            while(true) { val value=read();if(value<0)break;if(value==10)break;if(value!=13)text.append(value.toChar());check(text.length<=65536) }
            return text.toString()
        }
        private val worker=thread(name="Shortcuts HTTP fixture",isDaemon=true) {
            try { while(!socket.isClosed) socket.accept().use { client ->
                client.soTimeout=5000
                val input=client.getInputStream();val start=input.line().split(' ');if(start.size<2)return@use
                val headers=linkedMapOf<String,String>()
                while(true) { val line=input.line();if(line.isEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim() }
                val bytes=ByteArray(headers["content-length"]?.toInt()?:0);var offset=0
                while(offset<bytes.size) { val read=input.read(bytes,offset,bytes.size-offset);check(read>0);offset+=read }
                val request=Request(start[0],start[1],headers,bytes);requests+=request
                val response=reply(request);val body=response.body.toByteArray(Charsets.UTF_8)
                val head="HTTP/1.1 ${response.code} Test\r\nContent-Length: ${body.size}\r\nConnection: close\r\n"+response.headers.joinToString("") { "${it.first}: ${it.second}\r\n" }+"\r\n"
                client.getOutputStream().use { it.write(head.toByteArray(Charsets.UTF_8));if(request.method!="HEAD")it.write(body);it.flush() }
            } } catch(e:java.net.SocketException) { if(!socket.isClosed)throw e }
        }
        override fun close() { socket.close();worker.join(1000) }
    }
    @Test fun getPostPutPatchDeleteSendTheConfiguredHeadersAndJson() = runBlocking {
        LocalServer { Reply() }.use { server ->
            for(method in HttpActions.methods) {
                var p=mapOf("url" to server.url+"/api", "method" to method)
                p=writeEntrySpecs(p,"header","header",listOf(EntrySpec("text","X-Custom","test")))
                p=writeEntrySpecs(p,"bodyEntry","bodyEntry",listOf(EntrySpec("number","count","2")))
                val result=app.engine.run(Shortcut(name="Local HTTP",steps=listOf(ActionStep(type="get_url",parameters=p))),ExecutionMode.STANDARD)
                assertEquals(RunStatus.SUCCEEDED,result.status);assertEquals("ok",result.output)
                val received=server.requests.last();assertEquals(method,received.method);assertEquals("test",received.headers["x-custom"])
                assertEquals(if(method in listOf("POST","PUT","PATCH")) "{\"count\":2}" else "",received.body.toString(Charsets.UTF_8))
            }
        }
    }
    @Test fun expansionAndHeadersFollowRedirectsAndFallbackWhenHeadIsUnsupported() = runBlocking {
        LocalServer { request -> when {
            request.path=="/start" -> Reply(302,listOf("Location" to "/final"))
            request.path=="/loop" -> Reply(302,listOf("Location" to "/loop"))
            request.method=="HEAD" -> Reply(405)
            else -> Reply(headers=listOf("X-Many" to "a","X-Many" to "b"))
        } }.use { server ->
            suspend fun run(type:String)=app.engine.run(Shortcut(name="Local URL",steps=listOf(ActionStep(type=type,parameters=mapOf("url" to server.url+"/start")))),ExecutionMode.STANDARD)
            assertEquals(server.url+"/final",run("expand_url").output)
            val headers=run("get_url_headers").steps.last().value as Value.Dictionary
            assertEquals(setOf("a","b"),(headers.entries.getValue("x-many") as Value.ListValue).items.map{it.text()}.toSet())
            try { app.actions.requestHttp(HttpRequestSpec(server.url+"/loop"));fail("Redirect loop must fail") }catch(e:Problem){assertEquals("err_http_redirect",e.key)}
        }
    }
    @Test fun redirectsPreservePatchBodiesAndStripCredentialsAcrossOrigins() = runBlocking {
        LocalServer { Reply() }.use { target ->
            LocalServer { request -> Reply(if(request.path=="/post")302 else 307,listOf("Location" to target.url+"/destination")) }.use { origin ->
                val headers=mapOf("Authorization" to "Bearer local-test", "Content-Type" to "application/json", "X-Custom" to "retained")
                assertEquals(200,app.actions.requestHttp(HttpRequestSpec(origin.url+"/patch","PATCH",headers,"payload".toByteArray())).status)
                target.requests.last().let { assertEquals("PATCH",it.method);assertEquals("payload",it.body.toString(Charsets.UTF_8));assertNull(it.headers["authorization"]);assertEquals("retained",it.headers["x-custom"]) }
                assertEquals(200,app.actions.requestHttp(HttpRequestSpec(origin.url+"/post","POST",headers,"payload".toByteArray())).status)
                target.requests.last().let { assertEquals("GET",it.method);assertEquals(0,it.body.size);assertNull(it.headers["content-type"]);assertNull(it.headers["authorization"]) }
            }
        }
    }
}

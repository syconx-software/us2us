package com.syconx.us2us.runtime.net

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

// OkHttp resolves to its Android build in this module, which needs the Android runtime.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkPolicyTest {
    @Test
    fun onlyTheRelayOverHttps() {
        for (good in listOf("https://relay.us2us.app", "http://127.0.0.1:3000", "http://localhost:3000", "http://[::1]:3000", "https://relay.us2us.app/base")) {
            NetworkPolicy.validatedRelayUrl(good)
        }
        for (bad in listOf(
            "http://relay.us2us.app",
            "ftp://relay.us2us.app",
            "https://user:pw@relay.us2us.app",
            "https://relay.us2us.app?x=1",
            "https://relay.us2us.app?",
            "https://relay.us2us.app#top",
            "http://192.168.1.10:3000",
            "http://10.0.2.2:3000",
            "relay.us2us.app",
            "https://",
            "not a url",
        )) {
            assertThrows(bad, InsecureRelayUrlException::class.java) { NetworkPolicy.validatedRelayUrl(bad) }
        }
        assertThrows(InsecureRelayUrlException::class.java) { NetworkPolicy.makeRelayClient("http://relay.us2us.app") }
    }

    @Test
    fun requestsCarryNothingIdentifying() {
        val client = NetworkPolicy.httpClient()
        assertNull(client.cache)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        val tls = client.connectionSpecs.first { it.isTls }
        assertEquals(listOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2), tls.tlsVersions)
        assertEquals(listOf(TlsVersion.TLS_1_3), NetworkPolicy.httpClient(TlsVersion.TLS_1_3).connectionSpecs.first { it.isTls }.tlsVersions)

        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "https://elsewhere.example/").setHeader("Set-Cookie", "a=b").build())
            server.enqueue(MockResponse.Builder().code(200).body("{}").build())
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val request = Request.Builder().url(server.url("/v1/health")).header("User-Agent", "okhttp/5").build()
            client.newCall(request).execute().use { response -> assertEquals(302, response.code) }
            client.newCall(Request.Builder().url(server.url("/v1/health")).build()).execute().close()
            val first = server.takeRequest()
            assertEquals("us2us", first.headers["User-Agent"])
            assertEquals("en", first.headers["Accept-Language"])
            val second = server.takeRequest()
            assertNull("no cookie is kept", second.headers["Cookie"])
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun theRelayClientUsesThePolicy() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("""{"status":"ok","version":"1"}""").build())
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val url = "http://127.0.0.1:${server.port}/"
            val relay = NetworkPolicy.makeRelayClient(url)
            runCatching { relay.health() }
            val request = server.takeRequest()
            assertEquals("us2us", request.headers["User-Agent"])
            assertTrue(request.target.endsWith("/v1/health"))
        }
    }
}

package com.syconx.us2us.runtime.net

import com.syconx.us2us.core.relay.RelayClient
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.TlsVersion
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The relay URL is not `https`, or plain `http` to a host other than this device. */
class InsecureRelayUrlException : IOException("insecure relay url")

/**
 * The app talks to exactly one host: the relay. No analytics, crash reporting, link previews, CDNs
 * or third-party SDKs; everything the app sends goes through the one [RelayClient] built here.
 */
object NetworkPolicy {
    /**
     * The value sent as `User-Agent` and the language sent as `Accept-Language` on every request.
     * OkHttp would otherwise send its own name and version, which together with the platform's
     * defaults narrows down who is connecting.
     */
    const val USER_AGENT = "us2us"
    const val ACCEPT_LANGUAGE = "en"

    internal val loopbackHosts = setOf("127.0.0.1", "::1", "[::1]", "localhost")

    /** The longest a whole request may take (a large upload on a slow line). */
    private const val CALL_TIMEOUT_MINUTES = 15L

    /**
     * `https` only. Plain `http` is accepted for loopback addresses, which tests and the local
     * interop relay use (on the emulator through `adb reverse`); nothing else. No user, password,
     * query or fragment.
     *
     * @throws InsecureRelayUrlException otherwise.
     */
    fun validatedRelayUrl(url: String): HttpUrl {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            throw InsecureRelayUrlException()
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (scheme == null || host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            throw InsecureRelayUrlException()
        }
        val allowed = when (scheme) {
            "https" -> true
            "http" -> host in loopbackHosts
            else -> false
        }
        if (!allowed) throw InsecureRelayUrlException()
        return try {
            url.toHttpUrl()
        } catch (_: IllegalArgumentException) {
            throw InsecureRelayUrlException()
        }
    }

    /**
     * The HTTP client of the relay: the core's timeouts plus constant, non-identifying headers, no
     * cookies, no cache, TLS 1.2 or later and no redirects (the relay never redirects; following
     * one would take the auth token to another host). [RelayClient] keeps sender-hiding requests
     * (sealed and ephemeral sends, message proof-of-work, media download and delete) on a copy of
     * it with its own connection pool and TLS session cache (PROTOCOL.md §4.6 "Unlinkability");
     * both get this configuration.
     */
    fun httpClient(minimumTls: TlsVersion = TlsVersion.TLS_1_2): OkHttpClient =
        RelayClient.defaultHttpClient().newBuilder()
            .addInterceptor(PolicyHeaders)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionSpecs(listOf(tls(minimumTls), ConnectionSpec.CLEARTEXT))
            .callTimeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()

    /** The relay client of the app. @throws InsecureRelayUrlException for a URL [validatedRelayUrl] refuses. */
    fun makeRelayClient(baseUrl: String): RelayClient = RelayClient(validatedRelayUrl(baseUrl).toString(), httpClient())

    /** TLS 1.3 and 1.2, or 1.3 alone; never anything older, whatever [minimum] says. */
    private fun tls(minimum: TlsVersion): ConnectionSpec {
        val versions = if (minimum == TlsVersion.TLS_1_3) arrayOf(TlsVersion.TLS_1_3) else arrayOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        return ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(*versions).build()
    }

    /** Replaces whatever the request carried with the policy's headers. */
    private object PolicyHeaders : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .build(),
        )
    }
}

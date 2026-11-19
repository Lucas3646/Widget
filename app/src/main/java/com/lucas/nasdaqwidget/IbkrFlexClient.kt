package com.lucas.nasdaqwidget

import android.content.Context
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal object IbkrFlexClient {
    private const val BASE = "https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService"
    private const val CACHE_MILLIS = 15 * 60 * 1000L
    private var lastReport: Report? = null
    private var lastFailure: Failure? = null

    private data class Report(val credentials: BrokerConnectionStore.IbkrFlexCredentials, val at: Long, val xml: String)
    private data class Failure(val credentials: BrokerConnectionStore.IbkrFlexCredentials, val until: Long, val error: Exception)

    class FlexError(message: String, val retryable: Boolean) : IllegalStateException(message)

    @Synchronized
    fun statement(context: Context, credentials: BrokerConnectionStore.IbkrFlexCredentials, force: Boolean): String {
        require(BrokerConnectionStore.ibkrFlexCredentials(context) == credentials) { "Connexion IBKR modifiée" }
        val now = android.os.SystemClock.elapsedRealtime()
        val ttl = if (force) 30_000L else CACHE_MILLIS
        lastReport?.takeIf { it.credentials == credentials && now - it.at < ttl }?.let { return it.xml }
        lastFailure?.takeIf { it.credentials == credentials && now < it.until }?.let { throw it.error }
        lastReport = null
        val xml = try {
            download(credentials)
        } catch (e: Exception) {
            lastFailure = Failure(credentials, android.os.SystemClock.elapsedRealtime() + 60_000L, e)
            throw e
        }
        lastFailure = null
        lastReport = Report(credentials, android.os.SystemClock.elapsedRealtime(), xml)
        return xml
    }

    private fun download(credentials: BrokerConnectionStore.IbkrFlexCredentials): String {
        val token = encode(credentials.token)
        val request = get("SendRequest", token, encode(credentials.queryId))
        val reference = tag(request, "ReferenceCode") ?: throw responseError(request)
        repeat(6) { attempt ->
            Thread.sleep(if (attempt == 0) 1_200L else 2_000L)
            val report = get("GetStatement", token, encode(reference))
            if (Regex("<FlexQueryResponse(?:\\s|>)").containsMatchIn(report)) return report
            if (tag(report, "ErrorCode") != "1019") throw responseError(report)
        }
        throw FlexError("Relevé IBKR en préparation", true)
    }

    private fun responseError(xml: String): FlexError {
        val code = tag(xml, "ErrorCode")
        val message = tag(xml, "ErrorMessage") ?: "Réponse Flex inattendue"
        return FlexError(if (code == null) message else "IBKR $code · $message",
            code in setOf("1001", "1004", "1005", "1006", "1007", "1008", "1009", "1018", "1019", "1021"))
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun get(path: String, token: String, query: String): String {
        val connection = (URL("$BASE/$path?t=$token&q=$query&v=3").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "MarketWidgets Android")
            setRequestProperty("Accept", "application/xml,text/xml")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw FlexError("IBKR HTTP $status", status == 429 || status >= 500)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: java.io.IOException) {
            throw FlexError("IBKR inaccessible", true)
        } finally {
            connection.disconnect()
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
}

internal fun flexDate(raw: String?): LocalDate? {
    val date = raw?.trim()?.substringBefore(';')?.substringBefore(' ')?.substringBefore('T') ?: return null
    return listOf(DateTimeFormatter.BASIC_ISO_DATE, DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("MM/dd/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT))
        .firstNotNullOfOrNull { format -> runCatching { LocalDate.parse(date, format) }.getOrNull() }
}

internal fun Map<String, String>.flexNumber(vararg keys: String): Double? =
    keys.firstNotNullOfOrNull { key -> this[key]?.trim()?.replace(",", "")?.toDoubleOrNull()?.takeIf(Double::isFinite) }

internal fun readFlex(xml: String, onTag: (String, Map<String, String>) -> Unit) {
    val factory = SAXParserFactory.newInstance().apply {
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    factory.newSAXParser().parse(InputSource(StringReader(xml)), object : DefaultHandler() {
        private var seenRoot = false

        override fun startElement(uri: String?, localName: String?, name: String, attributes: Attributes) {
            if (!seenRoot) {
                require(name == "FlexQueryResponse") { "Rapport Flex invalide" }
                seenRoot = true
            }
            val attrs = (0 until attributes.length).associate { attributes.getQName(it) to attributes.getValue(it) }
            onTag(name, attrs)
        }
    })
}

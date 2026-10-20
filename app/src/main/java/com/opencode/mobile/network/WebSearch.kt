package com.opencode.mobile.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class WebResult(
    val title: String,
    val url: String,
    val snippet: String,
)

object WebSearchParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val blockStart = Regex("""<div class="snippet [^"]*" data-pos="\d+" data-type="web"""")
    private val titleRegex = Regex("""<div class="title search-snippet-title[^"]*"[^>]*title="([^"]*)"""")
    private val titleTextRegex = Regex("""<div class="title search-snippet-title[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val linkRegex = Regex("""<a href="(https?://[^"]+)"""")
    private val snippetRegex = Regex("""<div class="generic-snippet[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(html: String, limit: Int = 6): List<WebResult> {
        val starts = blockStart.findAll(html).map { it.range.first }.toList()
        val results = mutableListOf<WebResult>()
        val seen = mutableSetOf<String>()
        for ((index, start) in starts.withIndex()) {
            val end = starts.getOrNull(index + 1) ?: (start + 8_000).coerceAtMost(html.length)
            val block = html.substring(start, end)
            val link = linkRegex.find(block)?.groupValues?.get(1) ?: continue
            if (!seen.add(link)) continue
            val title = titleRegex.find(block)?.groupValues?.get(1)
                ?: titleTextRegex.find(block)?.groupValues?.get(1)
                ?: continue
            val snippet = snippetRegex.find(block)?.groupValues?.get(1).orEmpty()
            results += WebResult(
                title = clean(title),
                url = link,
                snippet = clean(snippet).take(400),
            )
            if (results.size >= limit) break
        }
        return results
    }

    private val ddgBlockRegex = Regex("""<div[^>]*class="[^"]*web-result[^"]*"[^>]*>(.*?)</div>\s*</div>""", RegexOption.DOT_MATCHES_ALL)
    private val ddgLinkRegex = Regex("""<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val ddgSnippetRegex = Regex("""<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

    fun parseDuckDuckGoHtml(html: String, limit: Int = 6): List<WebResult> {
        val results = mutableListOf<WebResult>()
        val seen = mutableSetOf<String>()
        val blocks = ddgBlockRegex.findAll(html).map { it.groupValues[1] }.toList()
        for (block in blocks) {
            val linkMatch = ddgLinkRegex.find(block) ?: continue
            var rawUrl = linkMatch.groupValues[1]
            if (rawUrl.contains("duckduckgo.com/y.js")) continue
            if (rawUrl.contains("uddg=")) {
                val encoded = rawUrl.substringAfter("uddg=").substringBefore('&')
                rawUrl = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(rawUrl)
            }
            if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) continue
            if (!seen.add(rawUrl)) continue

            val rawTitle = linkMatch.groupValues[2]
            val rawSnippet = ddgSnippetRegex.find(block)?.groupValues?.get(1).orEmpty()
            results += WebResult(
                title = clean(rawTitle),
                url = rawUrl,
                snippet = clean(rawSnippet).take(400),
            )
            if (results.size >= limit) break
        }
        return results
    }

    fun parseInstantAnswer(jsonString: String, limit: Int = 6): List<WebResult> {
        val root = runCatching {
            json.parseToJsonElement(jsonString) as? JsonObject
        }.getOrNull() ?: return emptyList()

        val results = mutableListOf<WebResult>()
        val seen = mutableSetOf<String>()

        val abstract = (root["Abstract"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val abstractUrl = (root["AbstractURL"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (abstract.isNotBlank() && abstractUrl.isNotBlank()) {
            results += WebResult("DuckDuckGo summary", abstractUrl, abstract)
            seen.add(abstractUrl)
        }

        fun extractTopic(topic: JsonObject) {
            val text = (topic["Text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val url = (topic["FirstURL"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (text.isNotBlank() && url.isNotBlank() && seen.add(url)) {
                results += WebResult(clean(text).take(80), url, clean(text).take(400))
            }
        }

        (root["RelatedTopics"] as? JsonArray)?.forEach { element ->
            val topic = element as? JsonObject ?: return@forEach
            extractTopic(topic)
            (topic["Topics"] as? JsonArray)?.forEach { subElement ->
                (subElement as? JsonObject)?.let { extractTopic(it) }
            }
        }
        return results.take(limit)
    }

    fun clean(value: String): String {
        return value
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&hellip;", "…")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

class WebSearchClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val trimmed = query.trim().take(300)
        if (trimmed.isEmpty()) throw IOException("The search query was empty.")
        val braveResults = runCatching { searchBrave(trimmed) }.getOrElse { emptyList() }
        if (braveResults.isNotEmpty()) return@withContext format(trimmed, braveResults)

        val ddgResults = runCatching { searchDuckDuckGoHtml(trimmed) }.getOrElse { emptyList() }
        if (ddgResults.isNotEmpty()) return@withContext format(trimmed, ddgResults)

        val fallback = runCatching { searchInstantAnswer(trimmed) }.getOrElse { emptyList() }
        if (fallback.isNotEmpty()) return@withContext format(trimmed, fallback)

        "No web results found for \"$trimmed\". Try a different query."
    }

    private fun searchDuckDuckGoHtml(query: String): List<WebResult> {
        val formBody = FormBody.Builder()
            .add("q", query)
            .build()
        val request = Request.Builder()
            .url("https://html.duckduckgo.com/html/")
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .post(formBody)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body.string()
            return WebSearchParser.parseDuckDuckGoHtml(body)
        }
    }

    private fun searchBrave(query: String): List<WebResult> {
        val url = "https://search.brave.com".toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .addQueryParameter("source", "web")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Web search failed (HTTP ${response.code}).")
            val body = response.body.string()
            return WebSearchParser.parse(body)
        }
    }

    private fun searchInstantAnswer(query: String): List<WebResult> {
        val url = "https://api.duckduckgo.com".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("no_html", "1")
            .addQueryParameter("skip_disambig", "1")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body.string()
            return WebSearchParser.parseInstantAnswer(body)
        }
    }

    private fun format(query: String, results: List<WebResult>): String {
        val builder = StringBuilder("Web results for \"$query\":\n")
        results.take(6).forEachIndexed { index, result ->
            builder.append(index + 1)
                .append(". ")
                .append(result.title)
                .append('\n')
                .append("   ")
                .append(result.url)
                .append('\n')
            if (result.snippet.isNotBlank()) {
                builder.append("   ").append(result.snippet).append('\n')
            }
        }
        return builder.toString().take(6_000)
    }

    companion object {
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

package com.opencode.mobile.network

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSearchParserTest {
    @Test
    fun `parses brave result blocks`() {
        val html = """
            <div class="snippet abc" data-pos="0" data-type="web">
              <a href="https://example.com/page">
                <div class="title search-snippet-title line-clamp-1" title="Example result">Example result</div>
              </a>
              <div class="generic-snippet xyz">A <strong>useful</strong> snippet &amp; more.</div>
            </div>
            <div class="snippet abc" data-pos="1" data-type="web">
              <a href="https://second.example">
                <div class="title search-snippet-title line-clamp-1" title="Second result">Second result</div>
              </a>
              <div class="generic-snippet xyz">Another result.</div>
            </div>
        """.trimIndent()

        val results = WebSearchParser.parse(html)

        assertEquals(2, results.size)
        assertEquals("Example result", results[0].title)
        assertEquals("https://example.com/page", results[0].url)
        assertEquals("A useful snippet & more.", results[0].snippet)
        assertEquals("Second result", results[1].title)
    }

    @Test
    fun `returns empty when no result blocks exist`() {
        assertEquals(0, WebSearchParser.parse("<html>no results</html>").size)
    }

    @Test
    fun `parses duckduckgo html results and filters ads`() {
        val html = """
            <div class="result results_links results_links_deep result--ad ">
              <a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad=1">Ad Title</a>
              <a class="result__snippet" href="#">Ad Snippet</a>
            </div>
            </div>
            <div class="result results_links results_links_deep web-result ">
              <a rel="nofollow" class="result__a" href="https://kotlinlang.org/">Kotlin &amp; Language</a>
              <a class="result__snippet" href="https://kotlinlang.org/"><b>Kotlin</b> is concise &amp; safe.</a>
            </div>
            </div>
            <div class="result results_links results_links_deep web-result ">
              <a rel="nofollow" class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fandroid.com%2F">Android Dev</a>
              <a class="result__snippet" href="#">Learn Android development.</a>
            </div>
            </div>
        """.trimIndent()

        val results = WebSearchParser.parseDuckDuckGoHtml(html)

        assertEquals(2, results.size)
        assertEquals("Kotlin & Language", results[0].title)
        assertEquals("https://kotlinlang.org/", results[0].url)
        assertEquals("Kotlin is concise & safe.", results[0].snippet)
        assertEquals("Android Dev", results[1].title)
        assertEquals("https://android.com/", results[1].url)
    }

    @Test
    fun `parses instant answer with abstract and nested category topics`() {
        val json = """
            {
              "Abstract": "Kotlin is a cross-platform programming language.",
              "AbstractURL": "https://en.wikipedia.org/wiki/Kotlin_(programming_language)",
              "RelatedTopics": [
                {
                  "Text": "Kotlin Coroutines - Asynchronous programming in Kotlin",
                  "FirstURL": "https://kotlinlang.org/docs/coroutines-overview.html"
                },
                {
                  "Name": "Kotlin Ecosystem",
                  "Topics": [
                    {
                      "Text": "Ktor - Framework for connected applications",
                      "FirstURL": "https://ktor.io/"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val results = WebSearchParser.parseInstantAnswer(json)

        assertEquals(3, results.size)
        assertEquals("DuckDuckGo summary", results[0].title)
        assertEquals("https://en.wikipedia.org/wiki/Kotlin_(programming_language)", results[0].url)
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", results[1].url)
        assertEquals("https://ktor.io/", results[2].url)
    }
}

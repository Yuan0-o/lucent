package com.lucent.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A minimal, key-free web-search backend for the assistant's optional "Web search" tool.
 *
 * The assistant is otherwise entirely local: the only network call the app makes is to the AI
 * endpoint the user configured. Web search is opt-in for exactly that reason — it introduces a
 * second network destination, so it stays off until the user turns it on in Settings. When it is on,
 * the model can call the `web_search` tool and this client answers it.
 *
 * ### Why this was rewritten
 * The first version used only DuckDuckGo's *Instant Answer* API. That endpoint returns concise facts,
 * definitions, and disambiguation — and nothing at all for the far more common "what's new / find me
 * / look this up" query. In practice the assistant would "search several times" and come back empty,
 * because the API genuinely had nothing to give for an ordinary query. That was the bug.
 *
 * So this now fetches an actual **search-results page** — DuckDuckGo's no-JavaScript "lite" endpoint,
 * which is exactly what a lightweight text browser loads — and reads the organic results (title,
 * snippet, link) straight out of the returned HTML. Sources are tried in order and merged:
 *
 *  1. DuckDuckGo Lite (GET)  — the primary results list; simple, stable, no key.
 *  2. DuckDuckGo HTML (POST) — a fallback with the same content in a different layout.
 *  3. Instant Answer API     — a crisp direct answer/definition when one exists, added on top.
 *  4. Wikipedia search API   — a reliable last resort so encyclopedic questions still get *something*
 *                              even if DuckDuckGo is rate-limited or serves an anti-bot page.
 *
 * No API key, no account, no WebView — just HTTP and parsing, kept isolated here so swapping the
 * provider later is a change to this one file. Everything is best-effort and never throws: a total
 * network failure comes back as [Result.failure] so the tool reports it honestly instead of the
 * assistant pretending it looked something up when it couldn't.
 */
object WebSearchClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private const val LITE_ENDPOINT = "https://lite.duckduckgo.com/lite/"
    private const val HTML_ENDPOINT = "https://html.duckduckgo.com/html/"
    private const val INSTANT_ENDPOINT = "https://api.duckduckgo.com/"
    private const val WIKI_ENDPOINT = "https://en.wikipedia.org/w/api.php"

    // A browser-like UA — the results endpoints serve an empty/blocked page to obvious bots.
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private const val MAX_RESULTS = 6

    private data class SearchResult(val title: String, val url: String, val snippet: String)

    /**
     * Run a search and return a short, plain-text digest of what came back, or a failure.
     *
     * A working results list is never discarded just because one source failed: the sources are
     * additive, and the digest is returned as a success as long as *anything* usable was found. Only
     * a complete wash — every source empty, or the network unreachable — is reported as a failure the
     * assistant can pass on to the user honestly.
     */
    suspend fun search(query: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext Result.success("No search query was given.")

        var networkFailure: Exception? = null
        fun note(e: Exception) { if (networkFailure == null) networkFailure = e }

        // 1 + 2: real results page. Lite first; only fall back to the HTML endpoint if Lite gave
        // nothing (they carry the same results, so running both when the first worked is wasted work).
        var results: List<SearchResult> = try { fetchLiteResults(trimmed) } catch (e: Exception) { note(e); emptyList() }
        if (results.isEmpty()) {
            results = try { fetchHtmlResults(trimmed) } catch (e: Exception) { note(e); emptyList() }
        }

        // 3: crisp instant answer, if any (added above the list).
        val instant: String? = try { fetchInstantAnswer(trimmed) } catch (e: Exception) { note(e); null }

        // 4: Wikipedia last resort, only if the result page produced nothing.
        if (results.isEmpty()) {
            results = try { fetchWikipedia(trimmed) } catch (e: Exception) { note(e); emptyList() }
        }

        val hasInstant = !instant.isNullOrBlank()
        if (!hasInstant && results.isEmpty()) {
            // Copy to a local val: networkFailure is captured/mutated by note(), so it can't be
            // smart-cast to non-null directly.
            val failure = networkFailure
            return@withContext if (failure != null) {
                Result.failure(failure)
            } else {
                Result.success(
                    "Web search for \"$trimmed\" returned no clear results. Tell the user you " +
                        "couldn't find anything solid and, if useful, suggest a more specific wording."
                )
            }
        }

        val sb = StringBuilder()
        sb.append("Web search results for \"").append(trimmed).append("\":\n")
        if (hasInstant) sb.append("\nSummary: ").append(instant!!.trim()).append("\n")
        if (results.isNotEmpty()) {
            sb.append("\nTop results:\n")
            results.take(MAX_RESULTS).forEachIndexed { i, r ->
                sb.append(i + 1).append(". ").append(r.title)
                if (r.snippet.isNotBlank()) sb.append(" — ").append(r.snippet)
                if (r.url.isNotBlank()) sb.append(" (").append(r.url).append(")")
                sb.append("\n")
            }
        }
        sb.append(
            "\nUse these results to answer the user's question in their own language. Cite a source " +
                "link when it helps, and say plainly if the results don't actually settle the question."
        )
        Result.success(sb.toString().trim())
    }

    // ---- DuckDuckGo Lite (primary) ----

    private fun fetchLiteResults(query: String): List<SearchResult> {
        val url = LITE_ENDPOINT + "?q=" + URLEncoder.encode(query, "UTF-8") + "&kl=wt-wt"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            if (html.isBlank() || html.contains("anomaly-modal", ignoreCase = true)) return emptyList()
            return parseResults(html, RESULT_LINK_LITE, RESULT_SNIPPET_LITE)
        }
    }

    // ---- DuckDuckGo HTML (fallback) ----

    private fun fetchHtmlResults(query: String): List<SearchResult> {
        val body = okhttp3.FormBody.Builder().add("q", query).add("kl", "wt-wt").build()
        val request = Request.Builder()
            .url(HTML_ENDPOINT)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            if (html.isBlank() || html.contains("anomaly-modal", ignoreCase = true)) return emptyList()
            return parseResults(html, RESULT_LINK_HTML, RESULT_SNIPPET_HTML)
        }
    }

    // The result anchors are matched by their class, wherever `href` sits in the tag (the Lite and
    // HTML layouts order the attributes differently), then href is pulled out of the matched tag.
    // Single OR double quotes are accepted for every attribute, since Lite uses single quotes.
    private val RESULT_LINK_LITE = Regex(
        """<a\b([^>]*\bclass=['"][^'"]*result-link[^'"]*['"][^>]*)>(.*?)</a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val RESULT_SNIPPET_LITE = Regex(
        """<td[^>]*\bclass=['"][^'"]*result-snippet[^'"]*['"][^>]*>(.*?)</td>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val RESULT_LINK_HTML = Regex(
        """<a\b([^>]*\bclass=['"][^'"]*result__a[^'"]*['"][^>]*)>(.*?)</a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val RESULT_SNIPPET_HTML = Regex(
        """<a[^>]*\bclass=['"][^'"]*result__snippet[^'"]*['"][^>]*>(.*?)</a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val HREF = Regex("""\bhref=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)

    private fun parseResults(html: String, linkRe: Regex, snippetRe: Regex): List<SearchResult> {
        val links = linkRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).map { cleanText(it.groupValues[1]) }.toList()

        val out = mutableListOf<SearchResult>()
        links.forEachIndexed { i, m ->
            val tagAttrs = m.groupValues[1]
            val title = cleanText(m.groupValues[2])
            if (title.isBlank()) return@forEachIndexed
            val href = HREF.find(tagAttrs)?.groupValues?.get(1).orEmpty()
            val url = resolveRedirect(href)
            val snippet = snippets.getOrNull(i).orEmpty()
            out += SearchResult(title, url, snippet)
            if (out.size >= MAX_RESULTS) return out
        }
        return out
    }

    /**
     * DuckDuckGo wraps each result URL in a redirector: `//duckduckgo.com/l/?uddg=<encoded real URL>`.
     * Pull the real URL back out of the `uddg` parameter; if the href isn't a redirector, use it as-is
     * (ensuring it has a scheme).
     */
    private fun resolveRedirect(href: String): String {
        val h = href.replace("&amp;", "&")
        val marker = "uddg="
        val idx = h.indexOf(marker)
        if (idx >= 0) {
            val start = idx + marker.length
            val end = h.indexOf('&', start).let { if (it < 0) h.length else it }
            val enc = h.substring(start, end)
            return try { URLDecoder.decode(enc, "UTF-8") } catch (e: Exception) { enc }
        }
        return when {
            h.startsWith("http") -> h
            h.startsWith("//") -> "https:$h"
            else -> h
        }
    }

    // ---- Instant Answer API (crisp facts / definitions) ----

    private fun fetchInstantAnswer(query: String): String? {
        val url = INSTANT_ENDPOINT + "?q=" + URLEncoder.encode(query, "UTF-8") +
            "&format=json&no_html=1&skip_disambig=1&t=lucent"
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val json = try { JSONObject(body) } catch (e: Exception) { return null }
            val sb = StringBuilder()
            val heading = json.optString("Heading", "")
            val abstract = json.optString("AbstractText", "").ifBlank { json.optString("Abstract", "") }
            val answer = json.optString("Answer", "")
            val definition = json.optString("Definition", "")
            val abstractUrl = json.optString("AbstractURL", "")
            if (answer.isNotBlank()) sb.append(answer).append(" ")
            if (abstract.isNotBlank()) {
                if (heading.isNotBlank()) sb.append(heading).append(": ")
                sb.append(abstract)
                if (abstractUrl.isNotBlank()) sb.append(" (source: ").append(abstractUrl).append(")")
                sb.append(" ")
            }
            if (definition.isNotBlank()) sb.append(definition)
            return sb.toString().trim().ifBlank { null }
        }
    }

    // ---- Wikipedia (reliable last resort) ----

    private fun fetchWikipedia(query: String): List<SearchResult> {
        val url = WIKI_ENDPOINT + "?action=query&list=search&format=json&srlimit=" + MAX_RESULTS +
            "&srsearch=" + URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            val arr = try {
                JSONObject(body).optJSONObject("query")?.optJSONArray("search")
            } catch (e: Exception) { null } ?: return emptyList()
            val out = mutableListOf<SearchResult>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title", "")
                if (title.isBlank()) continue
                val snippet = cleanText(o.optString("snippet", ""))
                val slug = title.replace(' ', '_')
                val encoded = try { URLEncoder.encode(slug, "UTF-8").replace("+", "%20") } catch (e: Exception) { slug }
                out += SearchResult("$title (Wikipedia)", "https://en.wikipedia.org/wiki/$encoded", snippet)
            }
            return out
        }
    }

    /** Strip HTML tags, decode the entities that actually show up, and collapse whitespace. */
    private fun cleanText(raw: String): String {
        val noTags = raw.replace(Regex("<[^>]+>"), "")
        val decoded = noTags
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&#x2F;", "/")
            .replace("&nbsp;", " ")
        return decoded.replace(Regex("\\s+"), " ").trim()
    }
}

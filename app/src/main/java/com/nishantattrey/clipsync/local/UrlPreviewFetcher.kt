package com.nishantattrey.clipsync.local

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.regex.Pattern

object UrlPreviewFetcher {
    private val cache = LruCache<String, String>(128)
    private val titlePattern = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val urlPattern = Pattern.compile("^https?://[\\w\\-]+(\\.[\\w\\-]+)*(:\\d+)?(/.*)?$", Pattern.CASE_INSENSITIVE)

    /**
     * Checks if the given text matches a basic http/https URL scheme.
     */
    fun isValidUrl(text: String): Boolean {
        val trimmed = text.trim()
        return urlPattern.matcher(trimmed).matches()
    }

    /**
     * Fetches the HTML title of the webpage at [urlText] asynchronously.
     * Enforces public DNS filtering, a 256KB body read limit, and a 4-second timeout.
     */
    suspend fun fetchPreview(urlText: String): String? = withContext(Dispatchers.IO) {
        val trimmed = urlText.trim()
        if (!isValidUrl(trimmed)) return@withContext null

        cache.get(trimmed)?.let { return@withContext it }

        runCatching {
            val url = URL(trimmed)
            val host = url.host ?: return@runCatching null

            // SSRF Protection: Resolve host and verify it's a public IP address
            val addresses = InetAddress.getAllByName(host)
            val isAllPublic = addresses.all { address ->
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress
            }
            if (!isAllPublic) return@runCatching null

            // Connect with strict timeouts
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "ClipSync-Android-Preview/1")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@runCatching null
            }

            // Stream with bounded read (limit to 256KB)
            val limit = 262144 // 256KB
            val buffer = ByteArray(4096)
            val outputStream = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                var totalBytesRead = 0
                while (totalBytesRead < limit) {
                    val read = input.read(buffer, 0, Math.min(buffer.size, limit - totalBytesRead))
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    totalBytesRead += read
                }
            }

            val contentType = connection.contentType ?: ""
            val charset = if (contentType.contains("charset=")) {
                contentType.substringAfter("charset=").substringBefore(";").trim()
            } else {
                "UTF-8"
            }

            val html = runCatching { outputStream.toString(charset) }.getOrElse { outputStream.toString("UTF-8") }
            val matcher = titlePattern.matcher(html)
            if (matcher.find()) {
                val title = matcher.group(1)?.trim()?.replace(Regex("\\s+"), " ")?.take(150)
                if (!title.isNullOrEmpty()) {
                    cache.put(trimmed, title)
                    return@runCatching title
                }
            }
            null
        }.getOrNull()
    }
}

package com.guernseyhill.notebook.sync

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The notebook's shared store is a private GitHub repository. Each day is a folder:
 *   days/YYYY-MM-DD/content.json   the Day Book's layer, written overnight by Claude
 *   days/YYYY-MM-DD/page.json      her layer: ink, stickers, frames, paper, fonts, ticks
 *   days/YYYY-MM-DD/photos/   the pictures she stuck on, as jpg
 * This class is the thin HTTP layer over the Contents API. Nothing clever lives here.
 */
class GitHubStore(
    private val owner: String,
    private val repo: String,
    private val branch: String,
    private val token: String,
) {
    class Remote(val sha: String, val bytes: ByteArray)
    class HttpError(val code: Int, message: String) : IOException(message)

    private val base = "https://api.github.com/repos/$owner/$repo"

    private fun enc(path: String) =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun open(method: String, url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 40_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "GuernseyHillNotebook")
        return c
    }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
            throw HttpError(code, "HTTP $code ${msg.ifBlank { text.take(120) }}")
        }
        return text
    }

    /** The file at path, or null if it does not exist. */
    suspend fun get(path: String): Remote? = withContext(Dispatchers.IO) {
        val c = open("GET", "$base/contents/${enc(path)}?ref=$branch")
        try {
            val o = JSONObject(read(c))
            val sha = o.getString("sha")
            val content = o.optString("content", "")
            // files over about 1 MB come back without content; fetch the blob by sha
            val bytes = if (content.isNotBlank()) Base64.decode(content, Base64.DEFAULT) else blob(sha)
            Remote(sha, bytes)
        } catch (e: HttpError) {
            if (e.code == 404) null else throw e
        } finally {
            c.disconnect()
        }
    }

    private fun blob(sha: String): ByteArray {
        val c = open("GET", "$base/git/blobs/$sha")
        try {
            val o = JSONObject(read(c))
            return Base64.decode(o.getString("content"), Base64.DEFAULT)
        } finally {
            c.disconnect()
        }
    }

    /** Names and shas of the files in a folder; empty if the folder is not there. */
    suspend fun list(path: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val c = open("GET", "$base/contents/${enc(path)}?ref=$branch")
        try {
            val text = read(c)
            if (!text.trimStart().startsWith("[")) return@withContext emptyList()
            val arr = org.json.JSONArray(text)
            (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); o.getString("name") to o.getString("sha") }
        } catch (e: HttpError) {
            if (e.code == 404) emptyList() else throw e
        } finally {
            c.disconnect()
        }
    }

    /** Create or replace a file. sha is the current one when replacing. Returns the new sha. */
    suspend fun put(path: String, bytes: ByteArray, sha: String?, message: String): String = withContext(Dispatchers.IO) {
        val c = open("PUT", "$base/contents/${enc(path)}")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject()
            .put("message", message)
            .put("branch", branch)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        if (sha != null) body.put("sha", sha)
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            JSONObject(read(c)).getJSONObject("content").getString("sha")
        } finally {
            c.disconnect()
        }
    }

    /** A one-line description of the repository, to prove the token and name are right. */
    suspend fun check(): String = withContext(Dispatchers.IO) {
        val c = open("GET", base)
        try {
            val o = JSONObject(read(c))
            val perms = o.optJSONObject("permissions")
            val canPush = perms?.optBoolean("push", false) ?: false
            val priv = if (o.optBoolean("private", false)) "private" else "PUBLIC"
            "Connected to ${o.getString("full_name")} ($priv, ${if (canPush) "can write" else "read only"})"
        } finally {
            c.disconnect()
        }
    }
}

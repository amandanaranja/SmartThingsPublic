package com.guernseyhill.notebook.sync

import android.content.Context
import com.guernseyhill.notebook.model.DayContent
import com.guernseyhill.notebook.model.PageModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Keeps the tablet and the repository agreeing, one day at a time.
 *
 * Content only ever flows in (Claude writes it, the notebook reads it).
 * Pages flow both ways; the last device to save wins, which is fine for one person.
 * Known shas live in a small preferences file so a pull can tell "new" from "same as before".
 */
class SyncManager(context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val shas = context.getSharedPreferences("sync_shas", Context.MODE_PRIVATE)
    private val contentDir = File(context.filesDir, "content").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var owner: String
        get() = prefs.getString("owner", "") ?: ""
        set(v) = prefs.edit().putString("owner", v.trim()).apply()
    var repo: String
        get() = prefs.getString("repo", "") ?: ""
        set(v) = prefs.edit().putString("repo", v.trim()).apply()
    var branch: String
        get() = prefs.getString("branch", "main")?.ifBlank { "main" } ?: "main"
        set(v) = prefs.edit().putString("branch", v.trim().ifBlank { "main" }).apply()
    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v.trim()).apply()

    val configured: Boolean get() = owner.isNotBlank() && repo.isNotBlank() && token.isNotBlank()

    @Volatile var status: String = "not set up"
        private set

    private fun store() = GitHubStore(owner, repo, branch, token)

    private fun contentPath(d: LocalDate) = "days/$d/content.json"
    private fun pagePath(d: String) = "days/$d/page.json"
    private fun photoPath(d: String, name: String) = "days/$d/photos/$name"

    private fun stamp() = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    private fun ok(what: String) { status = "$what ${stamp()}" }
    private fun fail(e: Throwable) { status = "sync failed: ${e.message ?: e.javaClass.simpleName}" }

    // ---------- content (in only) ----------

    fun cachedContent(d: LocalDate): DayContent? {
        val f = File(contentDir, "$d.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<DayContent>(f.readText()) }.getOrNull()
    }

    /** Fetches the day's content; null means Claude has not written that day. */
    suspend fun pullContent(d: LocalDate): DayContent? {
        val r = store().get(contentPath(d))
        val f = File(contentDir, "$d.json")
        if (r == null) { f.delete(); return null }
        f.writeBytes(r.bytes)
        return runCatching { json.decodeFromString<DayContent>(r.bytes.decodeToString()) }.getOrNull()
    }

    // ---------- pages (both ways) ----------

    /** A newer page from another device, or null when there is nothing new or local edits are waiting. */
    suspend fun pullPage(d: LocalDate, localDirty: Boolean): PageModel? {
        val path = pagePath(d.toString())
        val r = store().get(path) ?: return null
        if (r.sha == shas.getString(path, null)) return null
        if (localDirty) return null
        val page = runCatching { json.decodeFromString<PageModel>(r.bytes.decodeToString()) }.getOrNull() ?: return null
        shas.edit().putString(path, r.sha).apply()
        return page
    }

    suspend fun pushPage(page: PageModel) {
        val path = pagePath(page.date)
        val bytes = json.encodeToString(page).toByteArray()
        val message = "Notebook: ${page.date}"
        val known = shas.getString(path, null)
        val sha = try {
            store().put(path, bytes, known, message)
        } catch (e: GitHubStore.HttpError) {
            // someone else wrote first, or we never knew the sha: take the current one and write over it
            if (e.code == 409 || e.code == 422) store().put(path, bytes, store().get(path)?.sha, message) else throw e
        }
        shas.edit().putString(path, sha).apply()
    }

    // ---------- photos ----------

    suspend fun pushPhotos(page: PageModel, photoDir: File) {
        for (s in page.stickers) {
            if (s.kind != "photo" || s.text.isBlank()) continue
            val path = photoPath(page.date, s.text)
            if (shas.contains(path)) continue
            val f = File(photoDir, s.text)
            if (!f.exists()) continue
            val sha = try {
                store().put(path, f.readBytes(), null, "Notebook: photo for ${page.date}")
            } catch (e: GitHubStore.HttpError) {
                if (e.code == 422 || e.code == 409) store().get(path)?.sha ?: throw e else throw e
            }
            shas.edit().putString(path, sha).apply()
        }
    }

    /** Returns true if any photo was fetched, so the stickers can be rebuilt. */
    suspend fun pullPhotos(page: PageModel, photoDir: File): Boolean {
        var fetched = false
        for (s in page.stickers) {
            if (s.kind != "photo" || s.text.isBlank()) continue
            val f = File(photoDir, s.text)
            if (f.exists()) continue
            val r = store().get(photoPath(page.date, s.text)) ?: continue
            f.writeBytes(r.bytes)
            shas.edit().putString(photoPath(page.date, s.text), r.sha).apply()
            fetched = true
        }
        return fetched
    }

    // ---------- the whole day, as the notebook uses it ----------

    class Pulled(val content: DayContent?, val page: PageModel?, val photosFetched: Boolean)

    suspend fun pullDay(d: LocalDate, localPage: PageModel, localDirty: Boolean, photoDir: File): Pulled {
        try {
            val content = pullContent(d)
            val page = pullPage(d, localDirty)
            val photos = pullPhotos(page ?: localPage, photoDir)
            ok("synced")
            return Pulled(content, page, photos)
        } catch (e: Exception) {
            fail(e)
            throw e
        }
    }

    suspend fun pushDay(page: PageModel, photoDir: File) {
        try {
            pushPhotos(page, photoDir)
            pushPage(page)
            ok("saved to GitHub")
        } catch (e: Exception) {
            fail(e)
        }
    }

    suspend fun test(): String = try {
        val s = store().check()
        status = s
        s
    } catch (e: Exception) {
        fail(e)
        status
    }
}

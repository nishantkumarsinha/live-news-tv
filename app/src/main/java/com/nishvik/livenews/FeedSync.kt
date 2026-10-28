package com.nishvik.livenews

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Copies the channel list and logos from the web feed into this app's files folder.
 * The screen keeps reading those same local files, so when anything fails nothing changes
 * and whatever is already there (for example the list pushed over adb) stays in use.
 * Failures are written to logcat under the tag FeedSync.
 */
internal class FeedSync(private val dir: File, private val base: String) {

    // The list lives under a folder (.../test or .../prod). Logos are shared and live at the site root (/icons/...).
    private val origin = try {
        URL(base).let { it.protocol + "://" + it.authority }
    } catch (e: Exception) { base }
    private val etags = HashMap<String, String>()
    private var pendingTag: String? = null

    /** True when something on disk changed. Blocks, so call it off the main thread. */
    fun run(): Boolean {
        val raw = fetch("channels.json", 2_000_000, true)   // null: unchanged or failed
        val tag = pendingTag
        val replaced = raw != null && replaceList(raw, tag)
        // A logo that failed or was removed is fetched again on the next check, even when the list is the same.
        val local = File(dir, "channels.json")
        val got = fetchMissingLogos(readChannels(local))
        if (got > 0 && !replaced) local.setLastModified(System.currentTimeMillis())   // makes the screen redraw
        return replaced || got > 0
    }

    private fun replaceList(raw: ByteArray, tag: String?): Boolean {
        val root = try { JSONObject(String(raw, Charsets.UTF_8)) } catch (e: Exception) { return false }
        val arr = root.optJSONArray("channels") ?: return false
        if (arr.length() == 0) return false

        // Only replace the local list with a newer one (an adb push can be newer than the feed).
        val local = File(dir, "channels.json")
        val have = try {
            if (local.exists()) JSONObject(local.readText()).optLong("updated_epoch", 0) else -1L
        } catch (e: Exception) { -1L }
        if (have >= 0 && root.optLong("updated_epoch", 0) <= have) {
            tag?.let { etags["channels.json"] = it }
            return false
        }

        // Logos first, so the new list never shows tiles without them. A failed logo is skipped.
        fetchMissingLogos(arr)
        writeAtomic(local, raw)
        tag?.let { etags["channels.json"] = it }
        return true
    }

    private fun readChannels(f: File): JSONArray? = try {
        if (f.exists()) JSONObject(f.readText()).optJSONArray("channels") else null
    } catch (e: Exception) { null }

    /** Downloads the logos that are not on disk yet. Returns how many arrived. */
    private fun fetchMissingLogos(arr: JSONArray?): Int {
        if (arr == null) return 0
        val icons = File(dir, "icons").apply { mkdirs() }
        var got = 0
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("icon", "") ?: ""
            if (!ICON_OK.matches(name)) continue
            val f = File(icons, name)
            if (f.exists() && f.length() > 0) continue
            val img = fetch("icons/$name", 300_000, false) ?: continue
            try {
                writeAtomic(f, img)
                got++
            } catch (e: Exception) {
                Log.w(TAG, "could not save $name: $e")
            }
        }
        return got
    }

    private fun fetch(path: String, max: Int, tagged: Boolean): ByteArray? {
        var open: HttpURLConnection? = null
        try {
            val url = if (path.startsWith("icons/")) "$origin/$path" else "$base/$path"
            val c = URL(url).openConnection() as HttpURLConnection
            open = c
            c.connectTimeout = 8_000
            c.readTimeout = 15_000
            c.useCaches = false
            if (BuildConfig.FEED_KEY.isNotEmpty()) c.setRequestProperty("X-Feed-Key", BuildConfig.FEED_KEY)
            if (tagged) etags[path]?.let { c.setRequestProperty("If-None-Match", it) }
            val code = c.responseCode
            if (code != 200) {
                if (code != 304) Log.w(TAG, "$path HTTP $code")
                return null
            }
            if (c.contentLengthLong > max) {
                Log.w(TAG, "$path too large")
                return null
            }
            pendingTag = if (tagged) c.getHeaderField("ETag") else null
            val out = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) return null
                }
            }
            return out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "$path failed: $e")
            return null
        } finally {
            open?.disconnect()
        }
    }

    private fun writeAtomic(f: File, data: ByteArray) {
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private companion object {
        const val TAG = "FeedSync"
        val ICON_OK = Regex("[a-z0-9_]+\\.jpg")
    }
}

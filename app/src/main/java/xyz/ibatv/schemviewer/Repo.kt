package xyz.ibatv.schemviewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object Repo {
    private const val REPO = "IveBeenAl0ne/mobile-schematic-files"
    private const val FOLDER = "litematic"

    /** GitHub contents API lists the folder; files are downloaded from the raw host (no API quota). */
    const val LIST_URL = "https://api.github.com/repos/$REPO/contents/$FOLDER"
    const val RAW_BASE = "https://raw.githubusercontent.com/$REPO/HEAD/$FOLDER/"

    private lateinit var dir: File
    private val parsed = LruCache<String, Schematic>(4)
    private val thumbs = LruCache<String, Bitmap>(48)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val gate = Semaphore(3) // limit concurrent downloads/parses while the grid loads

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "schems").apply { mkdirs() }
    }

    private fun open(url: String): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "SchematicViewer")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val code = c.responseCode
        if (code == 403 || code == 429) throw IOException("GitHub is rate-limiting requests, try again in a few minutes")
        if (code !in 200..299) throw IOException("HTTP $code for $url")
        return c.inputStream
    }

    /** File names in the repo folder; falls back to already-downloaded files when the request fails. */
    suspend fun names(): List<String> = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray(open(LIST_URL).bufferedReader().use { it.readText() })
            val found = (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("type") == "file" && it.optString("name").endsWith(".litematic", ignoreCase = true) }
                .map { it.getString("name") }
                .sorted()
            if (found.isEmpty()) throw IOException("No .litematic files found in $REPO/$FOLDER")
            found
        } catch (e: Exception) {
            val cached = dir.listFiles { f -> f.name.endsWith(".litematic") }?.map { it.name }?.sorted().orEmpty()
            if (cached.isEmpty()) throw e else cached
        }
    }

    suspend fun schematic(name: String): Schematic = withContext(Dispatchers.IO) {
        locks.getOrPut(name) { Mutex() }.withLock {
            parsed[name] ?: run {
                val f = File(dir, name)
                if (!f.exists()) {
                    val tmp = File(dir, "$name.part")
                    open(RAW_BASE + Uri.encode(name)).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    tmp.renameTo(f)
                }
                val s = try {
                    f.inputStream().use { Schematic.parse(name, it) }
                } catch (e: Exception) {
                    f.delete() // corrupt download: retry next time
                    throw e
                }
                parsed.put(name, s)
                s
            }
        }
    }

    suspend fun thumb(name: String): Bitmap {
        thumbs[name]?.let { return it }
        return gate.withPermit {
            val s = schematic(name)
            val bmp = withContext(Dispatchers.Default) { Iso.render(s, 360) }
            thumbs.put(name, bmp)
            bmp
        }
    }
}

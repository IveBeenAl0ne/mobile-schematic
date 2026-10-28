package xyz.ibatv.schemviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Reads official vanilla Java Edition block PNGs from Mojang's published client JAR.
 * Textures are extracted on demand and cached in the app's private cache directory.
 * The version asset index does NOT contain the regular block textures; those live in client.jar.
 */
object MinecraftTextures {
    private const val VERSION = "1.21.4"
    private const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private const val USER_AGENT = "MobileSchematic/1.0"

    private lateinit var cacheDir: File
    private val mutex = Mutex()
    private var clientJar: File? = null
    private val bitmaps = object : LinkedHashMap<String, Bitmap>(96, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 160
    }

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "minecraft-vanilla-textures").apply { mkdirs() }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 45_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} while downloading Minecraft client")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private suspend fun ensureClientJar() = mutex.withLock {
        if (clientJar?.exists() == true) return@withLock
        val jar = File(cacheDir, "minecraft-$VERSION-client.jar")
        if (!jar.exists() || jar.length() == 0L) {
            val manifest = JSONObject(get(MANIFEST).toString(Charsets.UTF_8))
            val versions = manifest.getJSONArray("versions")
            var versionUrl: String? = null
            for (i in 0 until versions.length()) {
                val v = versions.getJSONObject(i)
                if (v.getString("id") == VERSION) {
                    versionUrl = v.getString("url")
                    break
                }
            }
            val vJson = JSONObject(get(versionUrl ?: error("Minecraft $VERSION was not found in Mojang's manifest")).toString(Charsets.UTF_8))
            val clientUrl = vJson.getJSONObject("downloads").getJSONObject("client").getString("url")
            val temp = File(cacheDir, "minecraft-$VERSION-client.jar.part")
            try {
                temp.writeBytes(get(clientUrl))
                if (temp.length() < 1_000_000L) error("Downloaded Minecraft client archive is incomplete")
                if (jar.exists()) jar.delete()
                if (!temp.renameTo(jar)) error("Couldn't cache Minecraft client archive")
            } finally { temp.delete() }
        }
        clientJar = jar
    }

    private fun candidates(blockName: String, face: String): List<String> {
        val n = blockName.removePrefix("minecraft:")
        val specific = when (n) {
            "grass_block" -> if (face == "top") listOf("grass_block_top") else listOf("grass_block_side", "grass_block_side_overlay")
            "crafting_table" -> if (face == "top") listOf("crafting_table_top") else listOf("crafting_table_side")
            "furnace", "smoker", "blast_furnace" -> if (face == "top") listOf("${n}_top") else listOf("${n}_front", n)
            "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log", "crimson_stem", "warped_stem" ->
                if (face == "top") listOf("${n}_top", n) else listOf(n, "${n}_side")
            "oak_wood", "spruce_wood", "birch_wood", "jungle_wood", "acacia_wood", "dark_oak_wood", "mangrove_wood", "cherry_wood", "pale_oak_wood" ->
                listOf(n, n + "_top")
            "dirt_path" -> if (face == "top") listOf("dirt_path_top") else listOf("dirt_path_side", "dirt")
            "farmland" -> if (face == "top") listOf("farmland", "farmland_moist") else listOf("dirt")
            "podzol", "mycelium" -> if (face == "top") listOf("${n}_top") else listOf("${n}_side", "dirt")
            "water", "bubble_column" -> listOf("water_still")
            "lava" -> listOf("lava_still")
            "short_grass" -> listOf("short_grass")
            "potted_azure_bluet" -> listOf("azure_bluet")
            else -> listOf(n)
        }
        return (specific + listOf(n.substringBeforeLast('_', n), "stone")).distinct()
            .map { "assets/minecraft/textures/block/$it.png" }
    }

    suspend fun bitmap(blockName: String, face: String = "side"): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val candidates = candidates(blockName, face)
            synchronized(bitmaps) {
                candidates.firstNotNullOfOrNull { bitmaps[it] }?.let { return@withContext it }
            }
            ensureClientJar()
            val jar = clientJar ?: return@withContext null
            ZipFile(jar).use { zip ->
                val entry = candidates.firstNotNullOfOrNull { zip.getEntry(it) }
                    ?: return@withContext null
                val decoded = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                    ?: return@withContext null
                synchronized(bitmaps) { bitmaps[entry.name] = decoded }
                // Keep extracted files too so we don't have to open the JAR for every new screen.
                val extracted = File(cacheDir, entry.name.substringAfterLast('/'))
                if (!extracted.exists()) runCatching {
                    zip.getInputStream(entry).use { input -> extracted.outputStream().use { output -> input.copyTo(output) } }
                }
                decoded
            }
        }.getOrNull()
    }
}

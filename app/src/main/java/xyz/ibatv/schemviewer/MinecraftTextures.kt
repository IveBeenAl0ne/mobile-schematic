package xyz.ibatv.schemviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads vanilla textures from Mojang's official version/asset manifests at runtime.
 * The copyrighted texture files are cached in app-private storage rather than committed
 * to this source repository. Missing/new block textures fall back to the palette colour.
 */
object MinecraftTextures {
    private const val VERSION = "1.21.4"
    private const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private const val USER_AGENT = "MobileSchematic/1.0"

    private lateinit var cacheDir: File
    private var index: Map<String, String> = emptyMap()
    private var initialized = false
    private val bitmaps = LinkedHashMap<String, Bitmap>(96, .75f, true)

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "minecraft-vanilla-textures").apply { mkdirs() }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} while downloading Minecraft assets")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private suspend fun ensureIndex() = withContext(Dispatchers.IO) {
        if (initialized) return@withContext
        val manifest = JSONObject(get(MANIFEST).toString(Charsets.UTF_8))
        val versions = manifest.getJSONArray("versions")
        var versionUrl: String? = null
        for (i in 0 until versions.length()) {
            val v = versions.getJSONObject(i)
            if (v.getString("id") == VERSION) { versionUrl = v.getString("url"); break }
        }
        val vJson = JSONObject(get(versionUrl ?: error("Minecraft version $VERSION is not in Mojang's manifest")).toString(Charsets.UTF_8))
        val assetIndex = vJson.getJSONObject("assetIndex")
        val idx = JSONObject(get(assetIndex.getString("url")).toString(Charsets.UTF_8))
        val objects = idx.getJSONObject("objects")
        val found = HashMap<String, String>(objects.length())
        val keys = objects.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = objects.getJSONObject(key)
            found[key] = value.getString("hash")
        }
        index = found
        initialized = true
    }

    private fun textureKey(blockName: String, face: String): String {
        val n = blockName.removePrefix("minecraft:")
        val base = when (n) {
            "grass_block" -> if (face == "top") "grass_block_top" else "grass_block_side"
            "dirt_path", "farmland" -> if (face == "top") if (n == "farmland") "farmland_moist" else "dirt_path_top" else if (n == "farmland") "farmland" else "dirt_path_side"
            "podzol", "mycelium" -> if (face == "top") "${n}_top" else "${n}_side"
            "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log", "mangrove_log", "cherry_log", "crimson_stem", "warped_stem" ->
                if (face == "top") "${n}_top" else n
            "oak_wood", "spruce_wood", "birch_wood", "jungle_wood", "acacia_wood", "dark_oak_wood", "mangrove_wood", "cherry_wood" ->
                if (face == "top") n else n
            "crafting_table" -> if (face == "top") "crafting_table_top" else "crafting_table_side"
            "furnace", "smoker", "blast_furnace" -> if (face == "top") "${n}_top" else "${n}_front"
            "dispenser", "dropper", "observer" -> if (face == "top") "${n}_front" else "${n}_front"
            "bookshelf" -> "bookshelf"
            "water", "bubble_column" -> "water_still"
            "lava" -> "lava_still"
            "short_grass" -> "short_grass"
            else -> n
        }
        return "minecraft/textures/block/$base.png"
    }

    suspend fun bitmap(blockName: String, face: String = "side"): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ensureIndex()
            val key = textureKey(blockName, face)
            synchronized(bitmaps) { bitmaps[key]?.let { return@withContext it } }
            val hash = index[key] ?: run {
                // Some names in the block palette refer to blocks whose model uses a shared texture.
                val n = blockName.removePrefix("minecraft:")
                val candidates = listOf(
                    "minecraft/textures/block/$n.png",
                    "minecraft/textures/block/${n.substringBefore('_')}.png",
                    "minecraft/textures/block/stone.png"
                )
                candidates.firstNotNullOfOrNull { index[it] } ?: return@withContext null
            }
            val file = File(cacheDir, "$hash.png")
            if (!file.exists()) {
                val bytes = get("https://resources.download.minecraft.net/${hash.take(2)}/$hash")
                file.writeBytes(bytes)
            }
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
            synchronized(bitmaps) {
                bitmaps[key] = bmp
                while (bitmaps.size > 128) bitmaps.remove(bitmaps.keys.first())
            }
            bmp
        }.getOrNull()
    }
}

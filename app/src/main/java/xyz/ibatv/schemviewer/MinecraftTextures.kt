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

/**
 * Loads vanilla block textures from Mojang's official asset object CDN.
 * Never substitutes stone for an unknown block: a missing mapping returns null.
 */
object MinecraftTextures {
    private const val VERSION = "1.21.4"
    private const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private lateinit var cacheDir: File
    private val indexMutex = Mutex()
    private var assetObjects: JSONObject? = null
    private val bitmapCache = object : LinkedHashMap<String, Bitmap>(96, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 192
    }

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "mc-assets-$VERSION").apply { mkdirs() }
    }

    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 45000
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}: $url")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun ensureAssetIndex() = indexMutex.withLock {
        if (assetObjects != null) return@withLock
        val cachedIndex = File(cacheDir, "asset-index.json")
        val index = if (cachedIndex.exists() && cachedIndex.length() > 0L) {
            JSONObject(cachedIndex.readText())
        } else {
            val manifest = JSONObject(download(MANIFEST_URL).toString(Charsets.UTF_8))
            val versions = manifest.getJSONArray("versions")
            var versionUrl: String? = null
            for (i in 0 until versions.length()) {
                val version = versions.getJSONObject(i)
                if (version.optString("id") == VERSION) {
                    versionUrl = version.getString("url")
                    break
                }
            }
            val versionJson = JSONObject(download(versionUrl ?: error("Minecraft version $VERSION is missing from Mojang's manifest")).toString(Charsets.UTF_8))
            val assetIndexUrl = versionJson.getJSONObject("assetIndex").getString("url")
            val bytes = download(assetIndexUrl)
            cachedIndex.writeBytes(bytes)
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
        assetObjects = index.getJSONObject("objects")
    }

    private fun candidatePaths(rawName: String, face: String): List<String> {
        // BlockState names may include serialized properties in some schematic formats.
        val name = rawName.substringBefore('[').removePrefix("minecraft:").trim()
        val textureNames = when {
            name == "grass_block" -> if (face == "top") listOf("grass_block_top") else listOf("grass_block_side")
            name == "crafting_table" -> if (face == "top") listOf("crafting_table_top") else listOf("crafting_table_side")
            name in setOf("furnace", "smoker", "blast_furnace") -> when (face) {
                "top" -> listOf("${name}_top")
                "front" -> listOf("${name}_front")
                else -> listOf("${name}_side")
            }
            name.endsWith("_log") || name.endsWith("_stem") -> if (face == "top") listOf("${name}_top") else listOf(name)
            name.endsWith("_wood") -> listOf(name)
            name == "dirt_path" -> if (face == "top") listOf("dirt_path_top") else listOf("dirt_path_side")
            name == "farmland" -> if (face == "top") listOf("farmland", "farmland_moist") else listOf("dirt")
            name == "podzol" || name == "mycelium" -> if (face == "top") listOf("${name}_top") else listOf("${name}_side")
            name == "water" || name == "bubble_column" -> listOf("water_still")
            name == "lava" -> listOf("lava_still")
            name == "short_grass" -> listOf("short_grass")
            name.startsWith("potted_") -> listOf(name.removePrefix("potted_"))
            // These blocks use the underlying material's texture, but do not guess unrelated names.
            name.endsWith("_slab") -> listOf(name.removeSuffix("_slab"))
            name.endsWith("_stairs") -> listOf(name.removeSuffix("_stairs"))
            name.endsWith("_wall") -> listOf(name.removeSuffix("_wall"))
            else -> listOf(name)
        }
        return textureNames.distinct().map { "minecraft/textures/block/$it.png" }
    }

    suspend fun bitmap(blockName: String, face: String = "side"): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ensureAssetIndex()
            val objects = assetObjects ?: return@withContext null
            for (path in candidatePaths(blockName, face)) {
                synchronized(bitmapCache) {
                    bitmapCache[path]?.let { return@withContext it }
                }
                val hash = objects.optJSONObject(path)?.optString("hash") ?: continue
                if (hash.length != 40) continue
                val file = File(cacheDir, "$hash.png")
                if (!file.exists() || file.length() == 0L) {
                    val temporary = File(cacheDir, "$hash.part")
                    try {
                        temporary.writeBytes(download("https://resources.download.minecraft.net/${hash.substring(0, 2)}/$hash"))
                        if (temporary.length() == 0L) continue
                        if (file.exists()) file.delete()
                        if (!temporary.renameTo(file)) {
                            temporary.copyTo(file, overwrite = true)
                            temporary.delete()
                        }
                    } finally {
                        temporary.delete()
                    }
                }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                synchronized(bitmapCache) { bitmapCache[path] = bitmap }
                return@withContext bitmap
            }
            null
        }.getOrNull()
    }
}

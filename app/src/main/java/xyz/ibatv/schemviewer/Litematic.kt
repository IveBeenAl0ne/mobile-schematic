package xyz.ibatv.schemviewer

import android.graphics.Color
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.max

/** Minimal gzip + big-endian NBT reader. */
object Nbt {
    @Suppress("UNCHECKED_CAST")
    fun read(input: InputStream): Map<String, Any> {
        val d = DataInputStream(GZIPInputStream(input, 1 shl 16).buffered(1 shl 16))
        require(d.readByte().toInt() == 10) { "Not an NBT file" }
        d.readUTF()
        return tag(d, 10) as Map<String, Any>
    }

    private fun tag(d: DataInputStream, t: Int): Any = when (t) {
        1 -> d.readByte()
        2 -> d.readShort()
        3 -> d.readInt()
        4 -> d.readLong()
        5 -> d.readFloat()
        6 -> d.readDouble()
        7 -> ByteArray(d.readInt()).also { d.readFully(it) }
        8 -> d.readUTF()
        9 -> {
            val et = d.readByte().toInt()
            List(d.readInt()) { tag(d, et) }
        }
        10 -> {
            val m = HashMap<String, Any>()
            while (true) {
                val tt = d.readByte().toInt()
                if (tt == 0) break
                val k = d.readUTF()
                m[k] = tag(d, tt)
            }
            m
        }
        11 -> IntArray(d.readInt()) { d.readInt() }
        12 -> LongArray(d.readInt()) { d.readLong() }
        else -> error("Bad NBT tag $t")
    }
}

/** One material line: an item and how many are needed. */
data class Mat(val item: String, val count: Int, val color: Int) {
    val label = item.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    val stacks get() = "${count / 64}×64" + if (count % 64 > 0) " + ${count % 64}" else ""
}

data class BlockState(val name: String, val props: Map<String, String>) {
    val isAir get() = name == "minecraft:air" || name == "minecraft:cave_air" || name == "minecraft:void_air"

    /** Items needed to place this block as (item id, quantity). Empty = free / part of another block. */
    fun items(): List<Pair<String, Int>> {
        val n = name.removePrefix("minecraft:")
        val qty = when {
            props["half"] == "upper" || props["part"] == "head" -> return emptyList() // door top, bed head
            n == "piston_head" || n == "moving_piston" -> return emptyList()
            (n == "water" || n == "lava") && props["level"] != "0" -> return emptyList()
            props["type"] == "double" -> 2                                           // double slab
            else -> props["candles"]?.toIntOrNull() ?: props["pickles"]?.toIntOrNull() ?: 1
        }
        if (n.startsWith("potted_")) return listOf("flower_pot" to 1, n.removePrefix("potted_") to 1)
        val item = ITEM[n] ?: n.replace("wall_torch", "torch").replace("_wall_", "_")
        return listOf(item to qty)
    }

    private companion object {
        val ITEM = mapOf(
            "redstone_wire" to "redstone", "sweet_berry_bush" to "sweet_berries", "wheat" to "wheat_seeds",
            "carrots" to "carrot", "potatoes" to "potato", "beetroots" to "beetroot_seeds", "tripwire" to "string",
            "water" to "water_bucket", "lava" to "lava_bucket",
        )
    }
}

private class Region(val nbt: Map<String, Any>, val min: IntArray, val size: IntArray)

class Schematic(
    val title: String,
    val author: String,
    val sx: Int, val sy: Int, val sz: Int,
    private val blocks: IntArray,   // index = (y * sz + z) * sx + x -> palette id (0 = air)
    val palette: List<BlockState>,
) {
    val colors = IntArray(palette.size) { Colors.of(palette[it].name) }

    fun at(x: Int, y: Int, z: Int) = blocks[(y * sz + z) * sx + x]

    /** Materials for one layer (y) or, with null, the whole build. Sorted by quantity. */
    fun materials(y: Int?): List<Mat> {
        val counts = IntArray(palette.size)
        val layer = sx * sz
        for (yy in if (y == null) 0 until sy else y..y) {
            for (i in yy * layer until (yy + 1) * layer) counts[blocks[i]]++
        }
        val out = HashMap<String, Mat>()
        for (id in 1 until palette.size) {
            if (counts[id] == 0) continue
            for ((item, per) in palette[id].items()) {
                val old = out[item]
                out[item] = Mat(item, (old?.count ?: 0) + counts[id] * per, old?.color ?: colors[id])
            }
        }
        return out.values.sortedWith(compareByDescending<Mat> { it.count }.thenBy { it.item })
    }

    companion object {
        private fun vec(m: Any?): IntArray {
            val v = m as Map<*, *>
            return intArrayOf((v["x"] as Number).toInt(), (v["y"] as Number).toInt(), (v["z"] as Number).toInt())
        }

        /** Litematica packs palette indices tightly, entries may straddle two longs. */
        private fun unpack(a: LongArray, i: Int, bits: Int, mask: Long): Int {
            val start = i.toLong() * bits
            val lo = (start shr 6).toInt()
            val off = (start and 63).toInt()
            val hi = ((start + bits - 1) shr 6).toInt()
            var v = a[lo] ushr off
            if (hi != lo) v = v or (a[hi] shl (64 - off))
            return (v and mask).toInt()
        }

        @Suppress("UNCHECKED_CAST")
        fun parse(fallbackName: String, input: InputStream): Schematic {
            val root = Nbt.read(input)
            val meta = root["Metadata"] as? Map<String, Any> ?: emptyMap()
            val regions = (root["Regions"] as Map<String, Map<String, Any>>).values.map { r ->
                val pos = vec(r["Position"])
                val size = vec(r["Size"])
                // negative sizes mean the region extends backwards from Position
                Region(r, IntArray(3) { if (size[it] < 0) pos[it] + size[it] + 1 else pos[it] }, IntArray(3) { abs(size[it]) })
            }
            val lo = IntArray(3) { i -> regions.minOf { it.min[i] } }
            val hi = IntArray(3) { i -> regions.maxOf { it.min[i] + it.size[i] } }
            val sx = hi[0] - lo[0]
            val sy = hi[1] - lo[1]
            val sz = hi[2] - lo[2]
            val blocks = IntArray(sx * sy * sz)
            val palette = arrayListOf(BlockState("minecraft:air", emptyMap()))
            val ids = HashMap<BlockState, Int>()

            for (r in regions) {
                val pal = (r.nbt["BlockStatePalette"] as List<Map<String, Any>>).map { p ->
                    val props = (p["Properties"] as? Map<String, Any>)?.mapValues { it.value as String } ?: emptyMap()
                    BlockState(p["Name"] as String, props)
                }
                val gid = IntArray(pal.size) { i ->
                    val st = pal[i]
                    if (st.isAir) 0 else ids.getOrPut(st) { palette.add(st); palette.size - 1 }
                }
                val bits = max(2, 32 - Integer.numberOfLeadingZeros(pal.size - 1))
                val mask = (1L shl bits) - 1
                val data = r.nbt["BlockStates"] as LongArray
                val (w, h, d) = r.size.toList()
                val ox = r.min[0] - lo[0]
                val oy = r.min[1] - lo[1]
                val oz = r.min[2] - lo[2]
                var i = 0
                for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
                    val g = gid[unpack(data, i++, bits, mask)]
                    if (g != 0) blocks[((y + oy) * sz + z + oz) * sx + x + ox] = g
                }
            }
            return Schematic(
                title = (meta["Name"] as? String)?.takeIf { it.isNotBlank() } ?: fallbackName.removeSuffix(".litematic"),
                author = meta["Author"] as? String ?: "",
                sx = sx, sy = sy, sz = sz, blocks = blocks, palette = palette,
            )
        }
    }
}

/** Block name -> display colour (dye colours first, then material keywords, then a stable hash colour). */
object Colors {
    private val dyes = linkedMapOf(
        "light_blue" to 0x3AAFD9, "light_gray" to 0x8E8E86, "white" to 0xE9ECEC, "orange" to 0xF07613,
        "magenta" to 0xBD44B3, "yellow" to 0xF8C527, "lime" to 0x70B919, "pink" to 0xED8DAC, "gray" to 0x3E4447,
        "cyan" to 0x158991, "purple" to 0x792AAC, "blue" to 0x35399D, "brown" to 0x724728, "green" to 0x546D1B,
        "red" to 0xA12722, "black" to 0x141519,
    )
    private val table = listOf(
        "water" to 0x3F76E4, "lava" to 0xE8630C, "glass" to 0xC0E4EE, "ice" to 0x91B8FF, "leaves" to 0x3F8A2E,
        "grass" to 0x5E9D34, "fern" to 0x4C7F2A, "azalea" to 0x5A8C3A, "moss" to 0x596D2D, "vine" to 0x3E7A2A,
        "sapling" to 0x5B8A30, "cactus" to 0x2D7A32, "bush" to 0x3F7A2E, "snow" to 0xF8FEFE,
        "sandstone" to 0xD9CC9E, "sand" to 0xDBCFA3, "gravel" to 0x837F7E, "clay" to 0xA4A8B8,
        "terracotta" to 0x985F45, "dirt" to 0x866043, "mud" to 0x3C393D, "podzol" to 0x5B3F1D,
        "dark_oak" to 0x4F3318, "spruce" to 0x7A5A34, "birch" to 0xD7C98B, "jungle" to 0xB88764,
        "acacia" to 0xBA6B3F, "mangrove" to 0x76362F, "cherry" to 0xE3B1B0, "bamboo" to 0xC9B04A,
        "crimson" to 0x7A2E4B, "warped" to 0x2B7E78, "oak" to 0xB8945F, "deepslate" to 0x4B4B50,
        "blackstone" to 0x2B2429, "netherrack" to 0x6F3535, "nether_brick" to 0x2C161A, "redstone" to 0xAA1F1F,
        "iron" to 0xD8D8D8, "gold" to 0xF9D74A, "diamond" to 0x4AEDD9, "emerald" to 0x2BCB62, "copper" to 0xC06C4B,
        "coal" to 0x2B2B2B, "quartz" to 0xEDE6DD, "obsidian" to 0x150F24, "andesite" to 0x888888,
        "granite" to 0x95675A, "diorite" to 0xBDBDBD, "tuff" to 0x6C6D64, "stone" to 0x7D7D7D, "brick" to 0x96564A,
        "furnace" to 0x6E6E6E, "smoker" to 0x6E6E6E, "anvil" to 0x3C3C3C, "grindstone" to 0x8A8A8A,
        "chest" to 0xA06B22, "barrel" to 0x8B6A3B, "crafting_table" to 0x9A6B3A, "bookshelf" to 0x7A5A34,
        "ladder" to 0x9A7A3F, "hay" to 0xCBA52B, "pumpkin" to 0xC8791A, "melon" to 0x7C9A2E,
        "lantern" to 0xF0B64A, "torch" to 0xF5C242, "candle" to 0xE8D9B8, "campfire" to 0xD0661A,
        "chain" to 0x3A3A44, "cobweb" to 0xE6E6E6, "enchanting" to 0x6A1B2A, "lectern" to 0x9A7A3F,
    )

    fun of(full: String): Int {
        val n = full.removePrefix("minecraft:")
        val tok = n.split('_')
        val rgb = dyes.entries.firstOrNull { (k, _) -> if ('_' in k) n.contains(k) else k in tok }?.value
            ?: table.firstOrNull { n.contains(it.first) }?.second
        if (rgb != null) return 0xFF000000.toInt() or rgb
        return Color.HSVToColor(floatArrayOf(((n.hashCode() and 0x7fffffff) % 360).toFloat(), 0.35f, 0.78f))
    }
}

package xyz.ibatv.schemviewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private enum class BlockView(val title: String) {
    ISO("Isometric"), TOP("Top-down"), SIDE("Side")
}

@Composable
fun InteractiveBlockViewer(s: Schematic, layer: Int) {
    var view by remember(s) { mutableStateOf(BlockView.ISO) }
    var zoom by remember(s) { mutableFloatStateOf(1f) }
    var pan by remember(s) { mutableStateOf(Offset.Zero) }
    var selected by remember(s) { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var textures by remember(s) { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var textureLoadFailed by remember(s) { mutableStateOf(false) }

    LaunchedEffect(s) {
        textures = emptyMap()
        textureLoadFailed = false
        val loaded = withContext(Dispatchers.IO) {
            val out = HashMap<String, ImageBitmap>()
            s.palette.asSequence().filterNot { it.isAir }.map { it.name }.distinct().forEach { name ->
                MinecraftTextures.bitmap(name, "top")?.let { out["$name|top"] = it.asImageBitmap() }
                MinecraftTextures.bitmap(name, "side")?.let { out["$name|side"] = it.asImageBitmap() }
            }
            out
        }
        textures = loaded
        textureLoadFailed = loaded.isEmpty()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            BlockView.values().forEach { v ->
                FilterChip(selected = view == v, onClick = { view = v }, label = { Text(v.title) })
            }
            FilterChip(selected = false, onClick = { zoom = (zoom / 1.25f).coerceIn(.35f, 8f) }, label = { Text("−") })
            FilterChip(selected = false, onClick = { zoom = (zoom * 1.25f).coerceIn(.35f, 8f) }, label = { Text("+") })
            FilterChip(selected = false, onClick = { zoom = 1f; pan = Offset.Zero }, label = { Text("Reset") })
        }
        Box(
            Modifier.fillMaxWidth().height(340.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
        ) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(view, s) {
                        detectTransformGestures { _, delta, scale, _ ->
                            zoom = (zoom * scale).coerceIn(.35f, 8f)
                            pan += delta
                        }
                    }
                    .pointerInput(view, s, layer, zoom, pan) {
                        detectTapGestures { p ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            when (view) {
                                BlockView.TOP -> {
                                    val cell = min(w / max(1, s.sx), h / max(1, s.sz)) * zoom
                                    val ox = (w - s.sx * cell) / 2f + pan.x
                                    val oy = (h - s.sz * cell) / 2f + pan.y
                                    val x = floor((p.x - ox) / cell).toInt()
                                    val z = floor((p.y - oy) / cell).toInt()
                                    if (x in 0 until s.sx && z in 0 until s.sz) selected = Triple(x, layer, z)
                                }
                                BlockView.SIDE -> {
                                    val cell = min(w / max(1, s.sx), h / max(1, s.sy)) * zoom
                                    val ox = (w - s.sx * cell) / 2f + pan.x
                                    val oy = (h - s.sy * cell) / 2f + pan.y
                                    val x = floor((p.x - ox) / cell).toInt()
                                    val y = s.sy - 1 - floor((p.y - oy) / cell).toInt()
                                    if (x in 0 until s.sx && y in 0 until s.sy) {
                                        val z = (s.sz - 1 downTo 0).firstOrNull { s.at(x, y, it) != 0 }
                                        if (z != null) selected = Triple(x, y, z)
                                    }
                                }
                                BlockView.ISO -> {
                                    val cell = isoCell(w, h, s) * zoom
                                    val ox = w / 2f - (s.sx - s.sz) / 2f * cell + pan.x
                                    val oy = h / 2f - ((s.sx + s.sz) / 2f - s.sy) / 2f * cell + pan.y
                                    var best: Triple<Int, Int, Int>? = null
                                    var bestDist = Float.MAX_VALUE
                                    // Test visible block centers from the current build slice; selecting a block
                                    // is based on projected position rather than treating the view as a flat grid.
                                    for (y in 0..layer.coerceAtMost(s.sy - 1)) for (x in 0 until s.sx) for (z in 0 until s.sz) {
                                        if (s.at(x, y, z) == 0) continue
                                        val cx = ox + (x - z + .5f - .5f) * cell
                                        val cy = oy + ((x + z + 1f) / 2f - y - .5f) * cell
                                        val dx = p.x - cx
                                        val dy = p.y - cy
                                        val d = dx * dx + dy * dy
                                        if (d < bestDist && abs(dx) < cell * 1.1f && abs(dy) < cell * .9f) {
                                            bestDist = d
                                            best = Triple(x, y, z)
                                        }
                                    }
                                    if (best != null) selected = best
                                }
                            }
                        }
                    }
            ) {
                drawRect(Color(0xFF20252A))
                when (view) {
                    BlockView.TOP -> {
                        val cell = (min(size.width / max(1, s.sx), size.height / max(1, s.sz)) * zoom).coerceAtLeast(1f)
                        val ox = (size.width - s.sx * cell) / 2f + pan.x
                        val oy = (size.height - s.sz * cell) / 2f + pan.y
                        for (z in 0 until s.sz) for (x in 0 until s.sx) {
                            val id = s.at(x, layer.coerceIn(0, s.sy - 1), z)
                            if (id == 0) continue
                            val state = s.palette[id]
                            val p = Offset(ox + x * cell, oy + z * cell)
                            val image = textures["${state.name}|top"] ?: textures["${state.name}|side"]
                            if (image != null) drawImage(image, dstOffset = IntOffset(p.x.toInt(), p.y.toInt()), dstSize = IntSize(cell.toInt().coerceAtLeast(1), cell.toInt().coerceAtLeast(1)))
                            else drawRect(Color(s.colors[id]), p, Size(cell, cell))
                            if (cell >= 8f) drawRect(Color.Black.copy(alpha = .25f), p, Size(cell, cell), style = Stroke(.7f))
                            if (selected == Triple(x, layer, z)) drawRect(Color(0xFFFFD54F), p, Size(cell, cell), style = Stroke(3f))
                        }
                    }
                    BlockView.SIDE -> {
                        val cell = (min(size.width / max(1, s.sx), size.height / max(1, s.sy)) * zoom).coerceAtLeast(1f)
                        val ox = (size.width - s.sx * cell) / 2f + pan.x
                        val oy = (size.height - s.sy * cell) / 2f + pan.y
                        for (y in 0 until s.sy) for (x in 0 until s.sx) {
                            val z = (s.sz - 1 downTo 0).firstOrNull { s.at(x, y, it) != 0 } ?: continue
                            val id = s.at(x, y, z)
                            val state = s.palette[id]
                            val p = Offset(ox + x * cell, oy + (s.sy - 1 - y) * cell)
                            val image = textures["${state.name}|side"] ?: textures["${state.name}|top"]
                            if (image != null) drawImage(image, dstOffset = IntOffset(p.x.toInt(), p.y.toInt()), dstSize = IntSize(cell.toInt().coerceAtLeast(1), cell.toInt().coerceAtLeast(1)))
                            else drawRect(Color(s.colors[id]), p, Size(cell, cell))
                            if (cell >= 8f) drawRect(Color.Black.copy(alpha = .25f), p, Size(cell, cell), style = Stroke(.7f))
                            if (selected == Triple(x, y, z)) drawRect(Color(0xFFFFD54F), p, Size(cell, cell), style = Stroke(3f))
                        }
                    }
                    BlockView.ISO -> {
                        val cell = isoCell(size.width, size.height, s) * zoom
                        val ox = size.width / 2f - (s.sx - s.sz) / 2f * cell + pan.x
                        val oy = size.height / 2f - ((s.sx + s.sz) / 2f - s.sy) / 2f * cell + pan.y
                        fun sx(x: Int, z: Int) = ox + (x - z) * cell
                        fun sy(x: Int, y: Int, z: Int) = oy + ((x + z) / 2f - y) * cell
                        fun facePath(points: List<Offset>): Path = Path().apply {
                            moveTo(points[0].x, points[0].y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        fun face(points: List<Offset>, image: ImageBitmap?, fallback: Color, shade: Float, selectedFace: Boolean) {
                            val path = facePath(points)
                            val left = points.minOf { it.x }
                            val top = points.minOf { it.y }
                            val right = points.maxOf { it.x }
                            val bottom = points.maxOf { it.y }
                            clipPath(path) {
                                if (image != null) {
                                    drawImage(image, dstOffset = IntOffset(left.toInt(), top.toInt()),
                                        dstSize = IntSize((right - left).toInt().coerceAtLeast(1), (bottom - top).toInt().coerceAtLeast(1)))
                                    if (shade < 1f) drawRect(Color.Black.copy(alpha = 1f - shade), Offset(left, top), Size(right - left, bottom - top))
                                } else drawRect(fallback.copy(alpha = 1f), Offset(left, top), Size(right - left, bottom - top))
                                drawPath(path, Color.Black.copy(alpha = .18f), style = Stroke(.7f))
                                if (selectedFace) drawPath(path, Color(0xFFFFD54F), style = Stroke(3f))
                            }
                        }
                        // Painter's order from back to front keeps nearer blocks over rear blocks.
                        for (y in 0..layer.coerceAtMost(s.sy - 1)) {
                            for (sum in 0..(s.sx + s.sz - 2)) {
                                for (x in 0 until s.sx) {
                                    val z = sum - x
                                    if (z !in 0 until s.sz) continue
                                    val id = s.at(x, y, z)
                                    if (id == 0) continue
                                    val state = s.palette[id]
                                    val topTex = textures["${state.name}|top"]
                                    val sideTex = textures["${state.name}|side"]
                                    val fallback = Color(s.colors[id])
                                    val isSelected = selected == Triple(x, y, z)
                                    val topPts = listOf(
                                        Offset(sx(x, z), sy(x, y + 1, z)),
                                        Offset(sx(x + 1, z), sy(x + 1, y + 1, z)),
                                        Offset(sx(x + 1, z + 1), sy(x + 1, y + 1, z + 1)),
                                        Offset(sx(x, z + 1), sy(x, y + 1, z + 1))
                                    )
                                    val xPts = listOf(
                                        Offset(sx(x + 1, z), sy(x + 1, y, z)),
                                        Offset(sx(x + 1, z + 1), sy(x + 1, y, z + 1)),
                                        Offset(sx(x + 1, z + 1), sy(x + 1, y + 1, z + 1)),
                                        Offset(sx(x + 1, z), sy(x + 1, y + 1, z))
                                    )
                                    val zPts = listOf(
                                        Offset(sx(x, z + 1), sy(x, y, z + 1)),
                                        Offset(sx(x + 1, z + 1), sy(x + 1, y, z + 1)),
                                        Offset(sx(x + 1, z + 1), sy(x + 1, y + 1, z + 1)),
                                        Offset(sx(x, z + 1), sy(x, y + 1, z + 1))
                                    )
                                    if (y == layer || s.at(x, y + 1, z) == 0) face(topPts, topTex, fallback, 1f, isSelected)
                                    if (x + 1 == s.sx || s.at(x + 1, y, z) == 0) face(xPts, sideTex, fallback, .60f, isSelected)
                                    if (z + 1 == s.sz || s.at(x, y, z + 1) == 0) face(zPts, sideTex, fallback, .78f, isSelected)
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                Modifier.align(Alignment.BottomStart).padding(8.dp),
                shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = .72f)
            ) {
                Text(
                    if (textureLoadFailed) "Textures unavailable · using fallback colours"
                    else if (textures.isEmpty()) "Loading Minecraft textures…"
                    else "Pinch to zoom · drag to pan · tap a block",
                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = Color.White, style = MaterialTheme.typography.labelSmall
                )
            }
        }
        val pos = selected
        if (pos != null && pos.first in 0 until s.sx && pos.second in 0 until s.sy && pos.third in 0 until s.sz) {
            val (x, y, z) = pos
            val id = s.at(x, y, z)
            val block = s.palette[id]
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Selected block", style = MaterialTheme.typography.titleSmall)
                    Text(if (id == 0) "minecraft:air" else block.name, style = MaterialTheme.typography.titleMedium)
                    Text("Position: X $x · Y $y · Z $z", style = MaterialTheme.typography.bodySmall)
                    if (block.props.isEmpty()) Text("Block state: no properties recorded", style = MaterialTheme.typography.bodySmall)
                    else block.props.toSortedMap().forEach { (key, value) -> Text("$key: $value", style = MaterialTheme.typography.bodyMedium) }
                    if (block.name.contains("slab")) {
                        val slabType = block.props["type"] ?: "not recorded"
                        Text("Slab placement: $slabType", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun isoCell(width: Float, height: Float, s: Schematic): Float {
    val byWidth = width / max(1f, (s.sx + s.sz).toFloat())
    val byHeight = height / max(1f, s.sy + (s.sx + s.sz) / 2f)
    return min(byWidth, byHeight) * .88f
}

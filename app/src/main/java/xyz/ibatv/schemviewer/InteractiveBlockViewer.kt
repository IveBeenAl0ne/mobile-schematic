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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private enum class BlockView(val title: String) { TOP("Top-down"), SIDE("Side"), ISO("Isometric") }

@Composable
fun InteractiveBlockViewer(s: Schematic, layer: Int) {
    var view by remember(s) { mutableStateOf(BlockView.ISO) }
    var zoom by remember(s) { mutableFloatStateOf(1f) }
    var pan by remember(s) { mutableStateOf(Offset.Zero) }
    var selected by remember(s) { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var textures by remember(s) { mutableStateOf<Map<String, android.graphics.Bitmap>>(emptyMap()) }

    LaunchedEffect(s) {
        textures = withContext(Dispatchers.IO) {
            val out = HashMap<String, android.graphics.Bitmap>()
            s.palette.asSequence().filterNot { it.isAir }.map { it.name }.distinct().forEach { name ->
                MinecraftTextures.bitmap(name, "top")?.let { out["$name|top"] = it }
                MinecraftTextures.bitmap(name, "side")?.let { out["$name|side"] = it }
            }
            out
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BlockView.entries.forEach { v ->
                FilterChip(selected = view == v, onClick = { view = v; zoom = 1f; pan = Offset.Zero }, label = { Text(v.title) })
            }
            FilterChip(selected = false, onClick = { zoom = (zoom / 1.25f).coerceIn(.5f, 8f) }, label = { Text("−") })
            FilterChip(selected = false, onClick = { zoom = (zoom * 1.25f).coerceIn(.5f, 8f) }, label = { Text("+") })
            FilterChip(selected = false, onClick = { zoom = 1f; pan = Offset.Zero }, label = { Text("Reset") })
        }
        Box(Modifier.fillMaxWidth().height(330.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))) {
            Canvas(Modifier.fillMaxSize()
                .pointerInput(view, s, layer) {
                    detectTransformGestures { centroid, delta, scale, _ ->
                        zoom = (zoom * scale).coerceIn(.5f, 8f)
                        pan += delta
                    }
                }
                .pointerInput(view, s, layer, zoom, pan) {
                    detectTapGestures { p ->
                        val cell = (min(size.width / max(1, if (view == BlockView.SIDE) s.sx else s.sx).toFloat(),
                            size.height / max(1, if (view == BlockView.SIDE) s.sy else if (view == BlockView.TOP) s.sz else s.sy).toFloat()) * zoom).coerceAtLeast(1f)
                        val ox = (size.width - (if (view == BlockView.SIDE) s.sx else s.sx) * cell) / 2f + pan.x
                        val oy = (size.height - (if (view == BlockView.SIDE) s.sy else if (view == BlockView.TOP) s.sz else s.sy) * cell) / 2f + pan.y
                        val a = floor((p.x - ox) / cell).toInt()
                        val b = floor((p.y - oy) / cell).toInt()
                        val coord = when (view) {
                            BlockView.TOP -> Triple(a, layer, b)
                            BlockView.SIDE -> Triple(a, b, s.sz / 2)
                            BlockView.ISO -> Triple(a, layer, b)
                        }
                        if (coord.first in 0 until s.sx && coord.second in 0 until s.sy && coord.third in 0 until s.sz) selected = coord
                    }
                }
            ) {
                val bg = Color(0xFF20252A)
                drawRect(bg)
                val (cols, rows) = when (view) {
                    BlockView.TOP -> s.sx to s.sz
                    BlockView.SIDE -> s.sx to s.sy
                    BlockView.ISO -> s.sx to s.sz
                }
                val cell = (min(size.width / max(1, cols), size.height / max(1, rows)) * zoom).coerceAtLeast(1f)
                val ox = (size.width - cols * cell) / 2f + pan.x
                val oy = (size.height - rows * cell) / 2f + pan.y
                for (a in 0 until cols) for (b in 0 until rows) {
                    val (x, y, z) = when (view) {
                        BlockView.TOP -> Triple(a, layer, b)
                        BlockView.SIDE -> Triple(a, b, s.sz / 2)
                        BlockView.ISO -> Triple(a, layer, b)
                    }
                    val id = s.at(x, y, z)
                    if (id == 0) continue
                    val state = s.palette[id]
                    val key = "${state.name}|${if (view == BlockView.TOP) "top" else "side"}"
                    val bmp = textures[key] ?: textures["${state.name}|side"]
                    val pos = Offset(ox + a * cell, oy + b * cell)
                    if (bmp != null) drawImage(bmp.asImageBitmap(), dstOffset = androidx.compose.ui.unit.IntOffset(pos.x.toInt(), pos.y.toInt()), dstSize = androidx.compose.ui.unit.IntSize(cell.toInt().coerceAtLeast(1), cell.toInt().coerceAtLeast(1)))
                    else drawRect(Color(s.colors[id]), pos, Size(cell, cell))
                    if (cell >= 8f) drawRect(Color.Black.copy(alpha = .25f), pos, Size(cell, cell), style = Stroke(.7f))
                    if (selected == Triple(x, y, z)) drawRect(Color(0xFFFFD54F), pos, Size(cell, cell), style = Stroke(3f))
                }
            }
            Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = .68f)) {
                Text("Pinch to zoom · drag to move · tap a block", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        val pos = selected
        if (pos != null) {
            val (x, y, z) = pos
            val id = s.at(x, y, z)
            val block = s.palette[id]
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Selected block", style = MaterialTheme.typography.titleSmall)
                    Text(if (id == 0) "minecraft:air" else block.name, style = MaterialTheme.typography.titleMedium)
                    Text("Position: X $x · Y $y · Z $z", style = MaterialTheme.typography.bodySmall)
                    if (block.props.isEmpty()) Text("Block state: no properties recorded", style = MaterialTheme.typography.bodySmall)
                    else block.props.toSortedMap().forEach { (key, value) ->
                        Text("$key: $value", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (block.name.contains("slab")) Text("Slab placement: ${block.props["type"] ?: "unknown"}; half=${block.props["type"] ?: block.props["half"] ?: "not recorded"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

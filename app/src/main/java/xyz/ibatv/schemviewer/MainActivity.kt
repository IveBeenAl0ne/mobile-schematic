package xyz.ibatv.schemviewer

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(applicationContext)
        MinecraftTextures.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(open != null) { open = null }
    val name = open
    if (name == null) Gallery { open = it } else Builder(name) { open = null }
}

// ---------- Gallery: remote list with isometric thumbnails ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Gallery(onOpen: (String) -> Unit) {
    var reload by rememberSaveable { mutableIntStateOf(0) }
    val list by produceState<Result<List<String>>?>(null, reload) {
        value = null
        value = runCatching { Repo.names() }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Schematics") }, actions = {
            IconButton({ reload++ }) { Icon(Icons.Default.Refresh, "Refresh") }
        })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) {
            val r = list
            when {
                r == null -> CircularProgressIndicator()
                r.isFailure -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load the schematic list", style = MaterialTheme.typography.titleMedium)
                    Text(r.exceptionOrNull()?.message ?: "", style = MaterialTheme.typography.bodySmall)
                    Button({ reload++ }, Modifier.padding(top = 12.dp)) { Text("Retry") }
                }
                else -> LazyVerticalGrid(
                    GridCells.Adaptive(150.dp), Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(r.getOrThrow(), key = { it }) { n ->
                        Card(onClick = { onOpen(n) }) {
                            Thumb(n)
                            Text(
                                n.removeSuffix(".litematic"), Modifier.padding(8.dp),
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Thumb(name: String) {
    val res by produceState<Result<Bitmap>?>(null, name) { value = runCatching { Repo.thumb(name) } }
    Box(Modifier.fillMaxWidth().aspectRatio(1f), Alignment.Center) {
        val r = res
        when {
            r == null -> CircularProgressIndicator(Modifier.size(24.dp))
            r.isSuccess -> Image(r.getOrThrow().asImageBitmap(), name, Modifier.fillMaxSize())
            else -> Text("⚠ unreadable", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------- Builder: layer-by-layer instructions ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Builder(name: String, onBack: () -> Unit) {
    val res by produceState<Result<Schematic>?>(null, name) { value = runCatching { Repo.schematic(name) } }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(name.removeSuffix(".litematic"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) {
            val r = res
            when {
                r == null -> CircularProgressIndicator()
                r.isFailure -> Text("Couldn't open this schematic: ${r.exceptionOrNull()?.message}", Modifier.padding(24.dp))
                else -> Layers(r.getOrThrow())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Layers(s: Schematic) {
    // Keep the Y index scoped to this schematic so another file cannot restore an invalid layer.
    var layer by remember(s) { mutableIntStateOf(0) }
    var total by remember(s) { mutableStateOf(false) }
    val preview by produceState<Bitmap?>(null, s, layer) {
        value = withContext(Dispatchers.Default) { Iso.render(s, 720, layer, layer) }
    }
    val here = remember(s, layer) { s.materials(layer) }
    val all by produceState(emptyList<Mat>(), s) { value = withContext(Dispatchers.Default) { s.materials(null) } }
    val shown = if (total) all else here

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(s.title, style = MaterialTheme.typography.titleLarge)
            Text(
                (if (s.author.isNotBlank()) "by ${s.author} · " else "") + "${s.sx} × ${s.sy} × ${s.sz} (W × H × D)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            InteractiveBlockViewer(s, layer)
        }
        item {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                OutlinedButton({ layer = max(0, layer - 1) }, enabled = layer > 0) { Text("◀ Prev") }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Layer ${layer + 1} of ${s.sy}", fontWeight = FontWeight.Bold)
                    Text("Y = $layer", style = MaterialTheme.typography.labelSmall)
                }
                Button({ layer = min(s.sy - 1, layer + 1) }, enabled = layer < s.sy - 1) { Text("Next ▶") }
            }
            if (s.sy > 1) {
                Slider(
                    value = layer.toFloat(),
                    onValueChange = { layer = it.roundToInt().coerceIn(0, s.sy - 1) },
                    valueRange = 0f..(s.sy - 1).toFloat(),
                    steps = (s.sy - 2).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!total, { total = false }, { Text("This layer") })
                FilterChip(total, { total = true }, { Text("Whole build") })
            }
            Text(
                "${shown.sumOf { it.count }} items · ${shown.size} materials",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
            )
        }
        items(shown, key = { it.item }) { MatRow(it) }
    }
}

@Composable
fun Plan(s: Schematic, y: Int) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val line = Color.Black.copy(alpha = 0.25f)
    Canvas(Modifier.fillMaxWidth().aspectRatio(s.sx.toFloat() / s.sz)) {
        drawRect(bg)
        val cell = size.width / s.sx
        for (z in 0 until s.sz) for (x in 0 until s.sx) {
            val id = s.at(x, y, z)
            if (id == 0) continue
            drawRect(Color(s.colors[id]), Offset(x * cell, z * cell), Size(cell, cell))
            if (cell > 9f) drawRect(line, Offset(x * cell, z * cell), Size(cell, cell), style = Stroke(1f))
        }
        for (x in 0..s.sx step 5) drawLine(line, Offset(x * cell, 0f), Offset(x * cell, size.height))
        for (z in 0..s.sz step 5) drawLine(line, Offset(0f, z * cell), Offset(size.width, z * cell))
    }
}

@Composable
fun MatRow(m: Mat) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).background(Color(m.color), RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Text(m.label, Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text("×${m.count}", fontWeight = FontWeight.Bold)
            if (m.count >= 64) Text(m.stacks, style = MaterialTheme.typography.bodySmall)
        }
    }
}

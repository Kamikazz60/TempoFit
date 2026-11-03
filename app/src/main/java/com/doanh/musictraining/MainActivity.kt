package com.doanh.musictraining

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.roundToInt

private val Navy = Color(0xFF08121F)
private val Panel = Color(0xFF152638)
private val Panel2 = Color(0xFF1B3045)
private val Violet = Color(0xFF8554FA)
private val Sky = Color(0xFF49B8FF)
private val Mint = Color(0xFF42E99B)
private val Muted = Color(0xFFAFBDD0)
private val White = Color(0xFFF5F8FF)
private val Danger = Color(0xFFFF6976)
private val Round = RoundedCornerShape(17.dp)

private data class Folder(val id: String, val name: String)
private data class Preset(val id: String, val name: String, val work: Int, val rest: Int, val rounds: Int)

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, MusicService::class.java))).buildAsync()
        future.addListener({
            controller = try { future.get() } catch (_: Exception) { null }
            setContent {
                MaterialTheme(colorScheme = darkColorScheme(primary = Violet, onPrimary = White, background = Navy, onBackground = White,
                    surface = Panel, onSurface = White, surfaceVariant = Panel2, onSurfaceVariant = Muted,
                    outline = Muted, secondary = Sky, onSecondary = Navy)) {
                    App(controller)
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }
    override fun onDestroy() { controller?.release(); controller = null; super.onDestroy() }

    private fun previewSignal(profile: String, volume: Float) {
        try {
            assets.openFd(if (profile == "grave") "work_grave.wav" else "work.wav").use { fd ->
                val gain = volume * TrainingService.SIGNAL_GAIN
                android.media.MediaPlayer().apply {
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    setVolume(gain, gain)
                    setOnCompletionListener { it.release() }
                    prepare(); start()
                }
            }
        } catch (_: Exception) { }
    }

    @Composable private fun App(player: MediaController?) {
        val prefs = remember { getSharedPreferences("music_training_v5", MODE_PRIVATE) }
        val musicDir = remember { File(filesDir, "music").apply { mkdirs() } }
        var tracks by remember { mutableStateOf(readTracks(musicDir)) }
        var folders by remember { mutableStateOf(readFolders(prefs.getString("folders", null))) }
        var assignments by remember { mutableStateOf(readAssignments(prefs.getString("assignments", null))) }
        var presets by remember { mutableStateOf(readPresets(prefs.getString("presets", null))) }
        var selectedFolder by remember { mutableStateOf(folders.first().id) }
        var tab by remember { mutableIntStateOf(0) }
        var search by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("Aucun morceau") }
        var playing by remember { mutableStateOf(false) }
        var position by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(0L) }
        var speed by remember { mutableFloatStateOf(1f) }
        var requestedSpeed by remember { mutableStateOf<Float?>(null) }
        var shuffle by remember { mutableStateOf(false) }
        var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
        var currentUri by remember { mutableStateOf<Uri?>(null) }
        var work by remember { mutableStateOf("40") }
        var rest by remember { mutableStateOf("20") }
        var prep by remember { mutableStateOf(prefs.getInt("prep_seconds", 10).toString()) }
        var rounds by remember { mutableStateOf("8") }
        var selectedPreset by remember { mutableStateOf<String?>(null) }
        var editFolder by remember { mutableStateOf<Folder?>(null) }
        var createFolder by remember { mutableStateOf(false) }
        var editPreset by remember { mutableStateOf<Preset?>(null) }
        var createPreset by remember { mutableStateOf(false) }
        var assignTrack by remember { mutableStateOf<File?>(null) }
        var error by remember { mutableStateOf("") }
        var signalVolume by remember { mutableFloatStateOf(prefs.getFloat("signal_volume", 1f).coerceIn(.1f, 1f)) }
        var signalProfile by remember { mutableStateOf(prefs.getString("signal_profile", "aigu") ?: "aigu") }
        val training by TrainingBus.state.collectAsState()

        fun persistFolders(next: List<Folder>) {
            folders = next; prefs.edit().putString("folders", JSONArray().apply {
                next.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
            }.toString()).apply()
        }
        fun persistAssignments(next: Map<String, Set<String>>) {
            assignments = next; prefs.edit().putString("assignments", JSONObject().apply {
                next.forEach { (file, ids) -> put(file, JSONArray(ids.toList())) }
            }.toString()).apply()
        }
        fun persistPresets(next: List<Preset>) {
            presets = next; prefs.edit().putString("presets", JSONArray().apply {
                next.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("work", it.work).put("rest", it.rest).put("rounds", it.rounds)) }
            }.toString()).apply()
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            var failures = 0
            uris.forEach { uri ->
                var target: File? = null
                try {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "audio.mp3"
                    target = File(musicDir, "${UUID.randomUUID()}_${name.replace(Regex("[^a-zA-Z0-9._ -]"), "_")}")
                    (contentResolver.openInputStream(uri) ?: error("Fichier illisible")).use { source ->
                        target!!.outputStream().use { dest -> source.copyTo(dest) }
                    }
                    persistAssignments(assignments + (target.name to setOf(selectedFolder)))
                } catch (_: Exception) { target?.delete(); failures++ }
            }
            tracks = readTracks(musicDir)
            error = if (failures > 0) "$failures import(s) impossible(s)" else ""
        }
        // Apply a tempo change only after the user has stopped moving the dial.
        // Reconfiguring the audio processor dozens of times per second can mute audio on devices.
        LaunchedEffect(player, requestedSpeed) {
            val target = requestedSpeed ?: return@LaunchedEffect
            delay(350)
            if (player != null && kotlin.math.abs(player.playbackParameters.speed - target) >= .005f) {
                player.playbackParameters = PlaybackParameters(target, 1f)
            }
            requestedSpeed = null
        }
        LaunchedEffect(player) {
            while (true) {
                if (player != null) {
                    playing = player.isPlaying
                    position = player.currentPosition.coerceAtLeast(0L)
                    duration = player.duration.coerceAtLeast(0L)
                    title = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Aucun morceau"
                    currentUri = player.currentMediaItem?.localConfiguration?.uri
                    if (requestedSpeed == null) speed = player.playbackParameters.speed
                    shuffle = player.shuffleModeEnabled
                    repeat = player.repeatMode
                }
                delay(250)
            }
        }
        fun playTrack(file: File) {
            if (player == null) return
            val queue = tracks.filter { selectedFolder in (assignments[it.name] ?: setOf("cardio")) }.ifEmpty { tracks }
            val index = queue.indexOfFirst { it.name == file.name }.coerceAtLeast(0)
            requestedSpeed = null
            speed = 1f
            player.playbackParameters = PlaybackParameters(1f, 1f)
            player.setMediaItems(queue.map {
                MediaItem.Builder().setUri(Uri.fromFile(it)).setMediaMetadata(MediaMetadata.Builder().setTitle(it.name.substringAfter('_')).build()).build()
            }, index, 0L)
            player.prepare(); player.play()
        }
        fun trainingCommand(action: String) {
            val intent = Intent(this, TrainingService::class.java).setAction(action)
            if (action == TrainingService.START) {
                intent.putExtra(TrainingService.WORK, (work.toIntOrNull() ?: 40).coerceIn(1, 36000))
                intent.putExtra(TrainingService.REST, (rest.toIntOrNull() ?: 20).coerceIn(1, 36000))
                intent.putExtra(TrainingService.PREP, (prep.toIntOrNull() ?: 0).coerceIn(0, 3600))
                intent.putExtra(TrainingService.ROUNDS, (rounds.toIntOrNull() ?: 8).coerceIn(1, 999))
                intent.putExtra(TrainingService.SIGNAL_VOLUME, signalVolume)
                intent.putExtra(TrainingService.SIGNAL_PROFILE, signalProfile)
                ContextCompat.startForegroundService(this, intent)
            } else startService(intent)
        }
        Column(Modifier.fillMaxSize().background(Navy)) {
            Row(Modifier.fillMaxWidth().padding(start = 19.dp, end = 12.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Logo", modifier = Modifier.size(46.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("TempoFit", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Votre musique. Votre entraînement.", color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = { tab = 2 }) { Text("⚙", color = White, fontSize = 22.sp) }
            }
            when (tab) {
                0 -> {
                    Column(Modifier.weight(1f).padding(horizontal = 15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Ma musique", color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { importer.launch(arrayOf("audio/*")) }) { Text("＋ Importer", color = Sky) }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            folders.forEach { folder ->
                                FilterChip(selected = selectedFolder == folder.id, onClick = { selectedFolder = folder.id },
                                    label = { Text(folder.name, color = White) }, colors = FilterChipDefaults.filterChipColors(
                                        containerColor = Panel, selectedContainerColor = Violet, labelColor = White))
                            }
                            IconButton(onClick = { createFolder = true }) { Text("＋", color = White, fontSize = 24.sp) }
                        }
                        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("Rechercher une musique…", color = Muted) }, shape = Round,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White))
                        if (error.isNotBlank()) Text(error, color = Danger, fontSize = 12.sp)
                        Spacer(Modifier.height(7.dp))
                        val visible = tracks.filter { selectedFolder in (assignments[it.name] ?: setOf("cardio")) && it.name.substringAfter('_').contains(search, true) }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(visible, key = { it.name }) { file ->
                                val selected = currentUri == Uri.fromFile(file)
                                Row(Modifier.fillMaxWidth().background(if (selected) Violet.copy(alpha = .35f) else Panel, Round)
                                    .clickable { playTrack(file) }.padding(start = 10.dp, end = 2.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(40.dp).background(Brush.linearGradient(listOf(Violet, Sky)), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                        Text("♫", color = White, fontSize = 24.sp)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(file.name.substringAfter('_'), color = White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                        Text(if (selected) "Lecture en cours" else "MP3 local", color = Muted, fontSize = 11.sp)
                                    }
                                    TextButton(onClick = { assignTrack = file }) { Text("⋮", color = White, fontSize = 23.sp) }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Column(Modifier.fillMaxWidth().background(Panel, Round).padding(horizontal = 12.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("TEMPO · BPM", color = Sky, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            TempoDial(requestedSpeed ?: speed, { requestedSpeed = it }, Modifier.size(158.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                listOf(.75f, 1f, 1.25f, 1.5f).forEach { factor ->
                                    val chosen = kotlin.math.abs((requestedSpeed ?: speed) - factor) < .005f
                                    TextButton(onClick = { requestedSpeed = factor },
                                        colors = ButtonDefaults.textButtonColors(containerColor = if (chosen) Violet else Panel2),
                                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp), modifier = Modifier.height(31.dp)) {
                                        Text("${(factor * 100).toInt()}%", color = White, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        PlayerControls(player, playing, position, duration, shuffle, repeat, compact = false)
                    }
                }
                1 -> {
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Entraînement", color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (!training.active) TextButton(onClick = { createPreset = true }) { Text("＋ Programme", color = Sky) }
                        }
                        if (!training.active) {
                            Text("PROGRAMMES", color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                presets.forEach { preset ->
                                    Column(Modifier.width(114.dp).background(if (selectedPreset == preset.id) Violet.copy(alpha = .4f) else Panel, Round)
                                        .clickable { selectedPreset = preset.id; work = preset.work.toString(); rest = preset.rest.toString(); rounds = preset.rounds.toString() }
                                        .padding(10.dp)) {
                                        Text(preset.name, color = White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                                        Spacer(Modifier.height(5.dp))
                                        Text("${preset.work}s / ${preset.rest}s", color = Muted, fontSize = 11.sp)
                                        Text("${preset.rounds} tours", color = Muted, fontSize = 11.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(15.dp))
                            Column(Modifier.fillMaxWidth().background(Panel, Round).padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                NumberField("Temps avant le départ (secondes)", prep, { prep = it.filter(Char::isDigit).take(4); prefs.edit().putInt("prep_seconds", prep.toIntOrNull() ?: 0).apply() }, Muted)
                                HorizontalDivider(color = Muted.copy(alpha = .2f))
                                NumberField("Travail (secondes)", work, { work = it.filter(Char::isDigit).take(5); selectedPreset = null }, Mint)
                                HorizontalDivider(color = Muted.copy(alpha = .2f))
                                NumberField("Repos (secondes)", rest, { rest = it.filter(Char::isDigit).take(5); selectedPreset = null }, Sky)
                                HorizontalDivider(color = Muted.copy(alpha = .2f))
                                NumberField("Répétitions", rounds, { rounds = it.filter(Char::isDigit).take(3); selectedPreset = null }, Violet)
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Travail : 1 bip   ·   Repos : 3 bips   ·   Fin : mélodie\nDécompte 3-2-1 avant chaque changement de phase", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { trainingCommand(TrainingService.START) }, modifier = Modifier.fillMaxWidth().height(52.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Navy), shape = Round) {
                                Text("▶  Démarrer l'entraînement", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            val isRest = training.phase.equals("Repos", true)
                            val isPrep = training.phase.equals("Préparation", true)
                            val phaseColor = if (isPrep) Violet else if (isRest) Sky else Mint
                            Spacer(Modifier.height(12.dp))
                            Text(if (isPrep) "Le premier tour démarre bientôt" else "Tour ${training.round} / ${training.rounds}", color = Muted)
                            Spacer(Modifier.height(16.dp))
                            Box(Modifier.size(252.dp).border(9.dp, phaseColor, CircleShape).background(phaseColor.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(if (isPrep || isRest) "◷" else "⚡", color = phaseColor, fontSize = 33.sp)
                                    Text(training.phase.uppercase(), color = phaseColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    Text(formatTime(training.seconds * 1000L), color = White, fontSize = 53.sp, fontWeight = FontWeight.Bold)
                                    Text(if (training.paused) "EN PAUSE" else if (isPrep) "Préparez-vous" else if (isRest) "Récupération" else "En cours", color = Muted, fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.height(22.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { trainingCommand(TrainingService.STOP) }, shape = CircleShape, modifier = Modifier.size(58.dp), contentPadding = PaddingValues(0.dp)) { Text("■", color = White) }
                                Button(onClick = { trainingCommand(if (training.paused) TrainingService.RESUME else TrainingService.PAUSE) },
                                    shape = CircleShape, modifier = Modifier.size(75.dp), contentPadding = PaddingValues(0.dp)) { Text(if (training.paused) "▶" else "Ⅱ", color = White, fontSize = 28.sp) }
                                OutlinedButton(onClick = { trainingCommand(TrainingService.NEXT) }, shape = CircleShape, modifier = Modifier.size(58.dp), contentPadding = PaddingValues(0.dp)) { Text("⏭", color = White) }
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                else -> {
                    LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { Text("Réglages", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
                        item { Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Dossiers musicaux", color = White, fontSize = 18.sp, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            TextButton(onClick = { createFolder = true }) { Text("＋ Ajouter", color = Sky) }
                        } }
                        items(folders, key = { "folder_${it.id}" }) { folder ->
                            Row(Modifier.fillMaxWidth().background(Panel, Round).padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("♫", color = Violet, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 10.dp))
                                Text(folder.name, color = White, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = { editFolder = folder }) { Text("✎", color = Sky) }
                                TextButton(onClick = {
                                    if (folders.size > 1) {
                                        val replacement = folders.first { it.id != folder.id }.id
                                        persistAssignments(assignments.mapValues { (_, ids) -> if (folder.id in ids) ids - folder.id + replacement else ids })
                                        persistFolders(folders.filterNot { it.id == folder.id })
                                        if (selectedFolder == folder.id) selectedFolder = replacement
                                    }
                                }, enabled = folders.size > 1) { Text("✕", color = Danger) }
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)); Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Programmes d'entraînement", color = White, fontSize = 18.sp, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            TextButton(onClick = { createPreset = true }) { Text("＋ Ajouter", color = Sky) }
                        } }
                        items(presets, key = { "preset_${it.id}" }) { preset ->
                            Row(Modifier.fillMaxWidth().background(Panel, Round).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(preset.name, color = White, fontWeight = FontWeight.SemiBold)
                                    Text("${preset.work}s / ${preset.rest}s · ${preset.rounds} tours", color = Muted, fontSize = 11.sp)
                                }
                                TextButton(onClick = { editPreset = preset }) { Text("✎", color = Sky) }
                                TextButton(onClick = { persistPresets(presets.filterNot { it.id == preset.id }) }) { Text("✕", color = Danger) }
                            }
                        }
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text("Signaux sonores", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        item {
                            Column(Modifier.fillMaxWidth().background(Panel, Round).padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Volume des bips", color = White, modifier = Modifier.weight(1f))
                                    Text("${(signalVolume * 100).roundToInt()}%", color = Sky, fontWeight = FontWeight.Bold)
                                }
                                Slider(value = signalVolume, onValueChange = { value ->
                                    signalVolume = value
                                    prefs.edit().putFloat("signal_volume", value).apply()
                                }, valueRange = .1f..1f)
                                Spacer(Modifier.height(8.dp))
                                Text("Tonalité des bips", color = White)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("aigu" to "Aiguë", "grave" to "Plus grave").forEach { (id, label) ->
                                        FilterChip(selected = signalProfile == id, onClick = {
                                            signalProfile = id
                                            prefs.edit().putString("signal_profile", id).apply()
                                            previewSignal(id, signalVolume)
                                        }, label = { Text(label, color = White) }, colors = FilterChipDefaults.filterChipColors(
                                            containerColor = Panel2, selectedContainerColor = Violet, labelColor = White))
                                    }
                                }
                                Text("Toucher une tonalité la fait écouter. Réglage indépendant des commandes du lecteur musical.", color = Muted, fontSize = 11.sp)
                            }
                        }
                        item { Text("Les musiques et les programmes sont conservés sur ce téléphone.", color = Muted, fontSize = 12.sp) }
                    }
                }
            }
            if (tab != 0) {
                Row(Modifier.fillMaxWidth().background(Panel, Round).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("♫", color = Sky, fontSize = 23.sp, modifier = Modifier.padding(end = 8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${(speed * 100).roundToInt()}% · ${formatTime(position)}", color = Muted, fontSize = 10.sp)
                    }
                    TextButton(onClick = { if (playing) player?.pause() else player?.play() }, enabled = player != null && player.mediaItemCount > 0) {
                        Text(if (playing) "Ⅱ" else "▶", color = White, fontSize = 23.sp)
                    }
                }
            }
            NavigationBar(containerColor = Navy, tonalElevation = 0.dp) {
                listOf("♫" to "Musique", "◷" to "Entraînement", "⚙" to "Réglages").forEachIndexed { index, entry ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Text(entry.first, color = if (tab == index) Violet else Muted, fontSize = 21.sp) },
                        label = { Text(entry.second, color = if (tab == index) White else Muted) })
                }
            }
        }
        if (createFolder || editFolder != null) {
            val old = editFolder
            NameDialog(if (old == null) "Nouveau dossier" else "Renommer le dossier", old?.name ?: "", { createFolder = false; editFolder = null }) { name ->
                if (old == null) persistFolders(folders + Folder(UUID.randomUUID().toString(), name))
                else persistFolders(folders.map { if (it.id == old.id) it.copy(name = name) else it })
                createFolder = false; editFolder = null
            }
        }
        if (assignTrack != null) {
            val file = assignTrack!!
            AlertDialog(onDismissRequest = { assignTrack = null }, title = { Text("Ranger la musique", color = White) },
                text = { Column {
                    Text(file.name.substringAfter('_'), color = Muted, fontSize = 12.sp)
                    folders.forEach { folder ->
                        val checked = folder.id in (assignments[file.name] ?: setOf("cardio"))
                        Row(Modifier.fillMaxWidth().clickable {
                            val next = (assignments[file.name] ?: setOf("cardio")).let { if (checked) it - folder.id else it + folder.id }
                            persistAssignments(assignments + (file.name to next))
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = { value ->
                                val next = (assignments[file.name] ?: setOf("cardio")).let { if (value) it + folder.id else it - folder.id }
                                persistAssignments(assignments + (file.name to next))
                            })
                            Text(folder.name, color = White)
                        }
                    }
                } },
                dismissButton = { TextButton(onClick = {
                    val deletingCurrent = currentUri == Uri.fromFile(file)
                    if (deletingCurrent) { player?.stop(); player?.clearMediaItems() }
                    if (file.delete()) {
                        persistAssignments(assignments - file.name)
                        tracks = readTracks(musicDir)
                        if (deletingCurrent) { title = "Aucun morceau"; currentUri = null; position = 0L; duration = 0L; playing = false }
                    } else error = "Impossible de supprimer ce morceau de l'application"
                    assignTrack = null
                }) { Text("Supprimer de l'application", color = Danger) } },
                confirmButton = { TextButton(onClick = { assignTrack = null }) { Text("Terminé", color = Sky) } }, containerColor = Panel)
        }
        if (createPreset || editPreset != null) {
            val old = editPreset
            PresetDialog(old, { createPreset = false; editPreset = null }) { new ->
                persistPresets(if (old == null) presets + new else presets.map { if (it.id == old.id) new else it })
                createPreset = false; editPreset = null
            }
        }
    }
}

@Composable private fun TempoDial(speed: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val start = 135f
    val sweep = 270f
    fun factor(point: Offset, size: Size): Float {
        val degrees = Math.toDegrees(atan2((point.y - size.height / 2).toDouble(), (point.x - size.width / 2).toDouble())).toFloat()
        val angle = ((degrees - start + 360f) % 360f).coerceIn(0f, sweep)
        return (.5f + angle / sweep * 1.5f).coerceIn(.5f, 2f)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { onChange(factor(it, Size(size.width.toFloat(), size.height.toFloat()))) }
        }.pointerInput(Unit) {
            detectDragGestures { change, _ ->
                onChange(factor(change.position, Size(size.width.toFloat(), size.height.toFloat())))
                change.consume()
            }
        }) {
            val width = 13.dp.toPx()
            val inset = width / 2 + 5.dp.toPx()
            val ringSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(Panel2, start, sweep, false, Offset(inset, inset), ringSize, style = Stroke(width, cap = StrokeCap.Round))
            val progress = ((speed.coerceIn(.5f, 2f) - .5f) / 1.5f)
            drawArc(brush = Brush.sweepGradient(listOf(Violet, Sky, Violet)), startAngle = start, sweepAngle = sweep * progress,
                useCenter = false, topLeft = Offset(inset, inset), size = ringSize, style = Stroke(width, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("BPM", color = Sky, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("${(speed * 100).roundToInt()}%", color = White, fontWeight = FontWeight.Bold, fontSize = 34.sp)
            Text("Hauteur conservée", color = Muted, fontSize = 9.sp)
        }
        Row(Modifier.fillMaxWidth().align(Alignment.Center), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onChange((speed - .01f).coerceAtLeast(.5f)) }, contentPadding = PaddingValues(0.dp)) { Text("−", color = White, fontSize = 23.sp) }
            TextButton(onClick = { onChange((speed + .01f).coerceAtMost(2f)) }, contentPadding = PaddingValues(0.dp)) { Text("+", color = White, fontSize = 23.sp) }
        }
    }
}

@Composable private fun PlayerControls(player: MediaController?, playing: Boolean, position: Long, duration: Long, shuffle: Boolean, repeat: Int, compact: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Slider(value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { fraction -> if (duration > 0) player?.seekTo((duration * fraction).toLong()) },
            enabled = duration > 0, modifier = Modifier.fillMaxWidth().height(30.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = Muted, fontSize = 10.sp)
            Text(formatTime(duration), color = Muted, fontSize = 10.sp)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { player?.shuffleModeEnabled = !shuffle }) { Text("⤨", color = if (shuffle) Mint else Muted, fontSize = 23.sp) }
            TextButton(onClick = { player?.seekToPrevious() }, enabled = player != null && player.mediaItemCount > 0) { Text("⏮", color = White, fontSize = 24.sp) }
            Button(onClick = { if (playing) player?.pause() else player?.play() }, enabled = player != null && player.mediaItemCount > 0,
                modifier = Modifier.size(53.dp), shape = CircleShape, contentPadding = PaddingValues(0.dp)) { Text(if (playing) "Ⅱ" else "▶", color = White, fontSize = 25.sp) }
            TextButton(onClick = { player?.seekToNext() }, enabled = player != null && player.mediaItemCount > 0) { Text("⏭", color = White, fontSize = 24.sp) }
            TextButton(onClick = { player?.repeatMode = when (repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }) {
                Text(if (repeat == Player.REPEAT_MODE_ONE) "↻¹" else "↻", color = if (repeat == Player.REPEAT_MODE_OFF) Muted else Mint, fontSize = 23.sp)
            }
        }
    }
}

@Composable private fun NumberField(label: String, value: String, onChange: (String) -> Unit, accent: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(accent.copy(alpha = .2f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) { Text("◷", color = accent) }
        Spacer(Modifier.width(9.dp))
        Text(label, color = White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(value, onChange, modifier = Modifier.width(88.dp), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = White, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold))
    }
}

@Composable private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title, color = White) },
        text = { OutlinedTextField(name, { name = it.take(32) }, singleLine = true, label = { Text("Nom", color = Muted) }) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) save(name.trim()) }, enabled = name.isNotBlank()) { Text("Enregistrer", color = Sky) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Annuler", color = Muted) } }, containerColor = Panel)
}

@Composable private fun PresetDialog(old: Preset?, dismiss: () -> Unit, save: (Preset) -> Unit) {
    var name by remember(old) { mutableStateOf(old?.name ?: "") }
    var work by remember(old) { mutableStateOf((old?.work ?: 40).toString()) }
    var rest by remember(old) { mutableStateOf((old?.rest ?: 20).toString()) }
    var rounds by remember(old) { mutableStateOf((old?.rounds ?: 8).toString()) }
    val valid = name.isNotBlank() && (work.toIntOrNull() ?: 0) in 1..36000 && (rest.toIntOrNull() ?: 0) in 1..36000 && (rounds.toIntOrNull() ?: 0) in 1..999
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (old == null) "Nouveau programme" else "Modifier le programme", color = White) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it.take(32) }, label = { Text("Nom", color = Muted) }, singleLine = true)
            NumberField("Travail (s)", work, { work = it.filter(Char::isDigit).take(5) }, Mint)
            NumberField("Repos (s)", rest, { rest = it.filter(Char::isDigit).take(5) }, Sky)
            NumberField("Répétitions", rounds, { rounds = it.filter(Char::isDigit).take(3) }, Violet)
        } }, confirmButton = { TextButton(onClick = { if (valid) save(Preset(old?.id ?: UUID.randomUUID().toString(), name.trim(), work.toInt(), rest.toInt(), rounds.toInt())) }, enabled = valid) { Text("Enregistrer", color = Sky) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Annuler", color = Muted) } }, containerColor = Panel)
}

private fun readTracks(dir: File): List<File> = dir.listFiles()?.filter { it.isFile && it.length() > 0L }?.sortedBy { it.name.substringAfter('_').lowercase() } ?: emptyList()
private fun readFolders(json: String?): List<Folder> = try {
    if (json == null) listOf(Folder("cardio", "Cardio"), Folder("renfo", "Renfo"), Folder("calme", "Calme"))
    else JSONArray(json).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Folder(o.getString("id"), o.getString("name")) } }.ifEmpty { listOf(Folder("cardio", "Cardio")) } }
} catch (_: Exception) { listOf(Folder("cardio", "Cardio"), Folder("renfo", "Renfo"), Folder("calme", "Calme")) }
private fun readAssignments(json: String?): Map<String, Set<String>> = try {
    if (json == null) emptyMap() else JSONObject(json).let { o -> o.keys().asSequence().associateWith { key -> o.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } } }
} catch (_: Exception) { emptyMap() }
private fun readPresets(json: String?): List<Preset> = try {
    if (json == null) listOf(Preset("hiit", "HIIT classique", 40, 20, 8), Preset("tabata", "Tabata", 20, 10, 8), Preset("renfo", "Renfo", 45, 15, 6))
    else JSONArray(json).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> Preset(o.getString("id"), o.getString("name"), o.getInt("work"), o.getInt("rest"), o.getInt("rounds")) } } }
} catch (_: Exception) { emptyList() }
private fun formatTime(ms: Long): String { val s = ms.coerceAtLeast(0L) / 1000L; return "%d:%02d".format(s / 60, s % 60) }

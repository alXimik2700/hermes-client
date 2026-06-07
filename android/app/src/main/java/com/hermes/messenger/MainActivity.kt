package com.hermes.messenger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hermes.messenger.data.HermesMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startService(Intent(this, HermesSyncService::class.java))
        AppConfig.initFromPrefs(this)
        SoundManager.init(this)
        setContent { HermesTheme { HermesApp() } }
    }
}

@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HermesColorScheme, content = content)
}

@Composable
fun HermesApp(viewModel: ChatViewModel = viewModel(), profileVM: ProfileViewModel = viewModel()) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val ctx = LocalContext.current
    val lang = remember { LocaleHelper.getLanguage(ctx) }
    val tr: (String) -> String = remember(lang) { { key -> LocaleHelper.getString(ctx, key, lang) } }
    val view = LocalView.current

    // Keyboard detector for MIUI/HyperOS
    var isKeyboardOpen by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            view.getWindowVisibleDisplayFrame(rect)
            val screenHeight = view.rootView.height
            val keypadHeight = screenHeight - rect.bottom
            isKeyboardOpen = keypadHeight > screenHeight * 0.15
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

    Scaffold(
        bottomBar = {
            if (!isKeyboardOpen && currentRoute != "fullscreen_image") {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(
                        Triple("chat", Icons.AutoMirrored.Filled.Chat, "nav_chat"),
                        Triple("profile", Icons.Filled.Person, "nav_profile"),
                        Triple("settings", Icons.Filled.Settings, "nav_settings"),
                    ).forEach { (route, icon, label) ->
                        NavigationBarItem(
                            icon = { Icon(icon, null) },
                            label = { Text(tr(label)) },
                            selected = currentRoute == route,
                            onClick = { navController.navigate(route) { popUpTo("chat") } },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.secondary,
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        NavHost(navController, "chat", Modifier.padding(innerPadding)) {
            composable("chat") { ChatScreen(viewModel, tr) }
            composable("profile") { ProfileScreen(tr, profileVM) }
            composable("settings") { SettingsScreen(tr) }
        }
    }
}

// =============================================================================
// CHAT SCREEN — Offline-First with media pickers
// =============================================================================

@Composable
fun ChatScreen(viewModel: ChatViewModel, tr: (String) -> String) {
    val ctx = LocalContext.current
    val rawMessages by viewModel.messages.collectAsStateWithLifecycle()
    // ASC by timestamp: oldest at top, newest at bottom
    // No re-sort needed — DB already returns ORDER BY timestamp ASC
    val messages = rawMessages
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    var fullscreenImageUrl by remember { mutableStateOf<String?>(null) }
    var lastMessageCount by remember { mutableIntStateOf(0) }
    var lastAiCount by remember { mutableIntStateOf(0) }
    var scrollForcedCount by remember { mutableIntStateOf(0) }

    // Scroll to bottom when new messages arrive or user is near bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && messages.size != lastMessageCount) {
            val nearBottom = try {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= messages.size - 3
            } catch (_: Exception) { true }
            val initialSync = scrollForcedCount < 10
            if (lastMessageCount == 0 || nearBottom || initialSync) {
                listState.scrollToItem(messages.size - 1)
                if (initialSync) scrollForcedCount++
            }
            // Play notification sound for new AI messages
            val aiCount = messages.count { it.isFromAgent }
            if (aiCount > lastAiCount && lastAiCount > 0) {
                SoundManager.play()
            }
            lastAiCount = aiCount
            lastMessageCount = messages.size
        }
    }

    // === PICKERS ===

    // Image picker — PickVisualMedia обходит MIUI Gallery bug
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendImage(it) }
    }

    // File picker — GetContent для документов/аудио/прочего
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleFilePick(ctx, it, viewModel) }
    }

    // === VOICE RECORDING: Hold-to-Record ===
    val hasAudioPermission = ContextCompat.checkSelfPermission(
        ctx, android.Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    var isRecording by remember { mutableStateOf(false) }
    var currentRecorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var lastVoiceFile by remember { mutableStateOf<java.io.File?>(null) }
    var voiceDraft by remember { mutableStateOf<java.io.File?>(null) }
    var recordingStartMs by remember { mutableLongStateOf(0L) }
    var recordingDuration by remember { mutableLongStateOf(0L) }

    // Timer tick while recording
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) {
                recordingDuration = System.currentTimeMillis() - recordingStartMs
                kotlinx.coroutines.delay(100)
            }
        }
    }

    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> /* permission state tracked via hasAudioPermission */ }

    fun startRecording(): Boolean {
        return try {
            val dir = java.io.File(ctx.cacheDir, "voice"); dir.mkdirs()
            val file = java.io.File(dir, "voice_${System.currentTimeMillis()}.m4a")
            val recorder = android.media.MediaRecorder()
            recorder.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(file.absolutePath)
                prepare(); start()
            }
            currentRecorder = recorder
            isRecording = true; lastVoiceFile = file
            recordingStartMs = System.currentTimeMillis()
            Toast.makeText(ctx, "Запись...", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(ctx, "Ошибка микрофона", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun cancelRecording() {
        voiceDraft = null
        currentRecorder?.apply { try { stop(); release() } catch (_: Exception) {} }
        currentRecorder = null
        isRecording = false
        lastVoiceFile?.delete()
        lastVoiceFile = null
    }

    fun stopRecording() {
        if (!isRecording) return
        currentRecorder?.apply { try { stop(); release() } catch (_: Exception) {} }
        currentRecorder = null
        isRecording = false
        val elapsed = System.currentTimeMillis() - recordingStartMs
        if (elapsed < 500) {
            cancelRecording()
            return
        }
        // Small delay to let MediaRecorder finalize file headers, then set draft
        scope.launch {
            delay(100)
            voiceDraft = lastVoiceFile
            Toast.makeText(ctx, "Записано ${elapsed / 1000}с", Toast.LENGTH_SHORT).show()
        }
    }

    fun sendVoiceDraft() {
        voiceDraft?.let { viewModel.sendVoice(Uri.fromFile(it), it.name) }
        voiceDraft = null
        Toast.makeText(ctx, "Отправлено", Toast.LENGTH_SHORT).show()
    }

    fun deleteVoiceDraft() {
        voiceDraft?.delete()
        voiceDraft = null
    }

    // === UI ===

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // Header
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center) {
                    Text("A", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("chat_title"), fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(4.dp), modifier = Modifier.size(6.dp),
                            color = if (connectionStatus is ConnectionStatus.Online) HermesColors.success else HermesColors.error) {}
                        Spacer(Modifier.width(4.dp))
                        Text(if (connectionStatus is ConnectionStatus.Online) tr("chat_online") else tr("chat_offline"),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text("[${messages.size}]", fontSize = 10.sp, color = HermesColors.textSecondary)
                        if (pendingCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text("[$pendingCount]", fontSize = 10.sp, color = Color(0xFFFFAB40))
                        }
                    }
                }
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                if (msg.isFromAgent) {
                    android.util.Log.e("ChatUI", "AI: ${msg.text.take(40)}")
                }
                MessageBubble(
                    msg = msg,
                    onImageClick = { fullscreenImageUrl = it }
                )
            }
        }

        // Fullscreen image overlay
        fullscreenImageUrl?.let { url ->
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable { fullscreenImageUrl = null },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(model = url, contentDescription = "Fullscreen",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }

        // Input bar — Telegram-style: icons outside, oval only for text
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // === LEFT: Icons outside oval ===
                    Row(
                        modifier = Modifier.padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { filePicker.launch("*/*") },
                            modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.AttachFile, null,
                                tint = HermesColors.textSecondary, modifier = Modifier.size(22.dp))
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    // === CENTER: Oval — only text, no emoji ===
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(24.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.fillMaxWidth(),
                            cursorBrush = SolidColor(Color.White),
                            minLines = 1,
                            maxLines = 6,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            decorationBox = { innerTextField ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (inputText.isEmpty()) {
                                        Text(tr("chat_placeholder"),
                                            color = HermesColors.textSecondary, fontSize = 16.sp)
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // === RIGHT: Mic (hold-to-record) / Send / Voice draft ===
                    if (voiceDraft != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { deleteVoiceDraft() }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Filled.Delete, null, tint = HermesColors.error, modifier = Modifier.size(24.dp))
                            }
                            Text("Голосовое записано", Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = { sendVoiceDraft() }) {
                                Icon(Icons.AutoMirrored.Filled.Send, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            }
                        }
                    } else if (inputText.isNotBlank()) {
                        Box(modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable { viewModel.sendMessage(inputText.trim()); inputText = "" },
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.Send, tr("send"), tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    } else {
                        // Mic/Stop: single Box, never replaced — pointerInput survives recomposition
                        Box(modifier = Modifier
                            .size(44.dp)
                            .then(
                                if (isRecording) Modifier.background(HermesColors.error, CircleShape)
                                else Modifier
                            )
                            .pointerInput(hasAudioPermission) {
                                if (!hasAudioPermission) {
                                    detectTapGestures {
                                        recordPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                                    }
                                } else {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            down.consume()
                                            if (!isRecording) {
                                                if (startRecording()) {
                                                    val up = waitForUpOrCancellation()
                                                    if (up != null) stopRecording() else cancelRecording()
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            contentAlignment = Alignment.Center) {
                            Icon(
                                if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                                null,
                                tint = if (isRecording) Color.White else HermesColors.textSecondary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// FILE PICKER HANDLER — extracts name/mime, calls sendFile
// =============================================================================

fun handleFilePick(ctx: android.content.Context, uri: Uri, viewModel: ChatViewModel) {
    try {
        val mimeType = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        var fileName = uri.lastPathSegment ?: "file.bin"
        if (!fileName.contains('.')) {
            fileName += when (mimeType) {
                "image/jpeg" -> ".jpg"; "image/png" -> ".png"; "image/gif" -> ".gif"
                "image/webp" -> ".webp"; "audio/mpeg" -> ".mp3"; "audio/wav" -> ".wav"
                "audio/mp4" -> ".m4a"; "video/mp4" -> ".mp4"; else -> ""
            }
        }
        viewModel.sendFile(uri, fileName, mimeType)
    } catch (_: Exception) {}
}

// =============================================================================
// MESSAGE BUBBLE — branches on messageType field
// =============================================================================

@Composable
fun MessageBubble(msg: HermesMessageEntity, onImageClick: ((String) -> Unit)? = null) {
    val isUser = !msg.isFromAgent
    val bubbleColor = if (isUser) HermesColors.userBubble else Color(0xFF4A5568)
    val textColor = if (isUser) Color.White else HermesColors.textMain
    val timeColor = if (isUser) Color.White.copy(alpha = 0.5f) else HermesColors.textSecondary.copy(alpha = 0.6f)
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // Resolve media URL: local file first, then server
    fun resolveMediaUrl(): String? {
        val local = msg.localFilePath
        if (local != null && java.io.File(local).exists()) return local
        val serverPath = msg.fileUrl ?: return null
        return "${AppConfig.currentServerUrl}${serverPath.removePrefix("/")}"
    }

    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(bubbleColor, RoundedCornerShape(16.dp))
                .padding(8.dp)
        ) {
                // Media content based on type
                when (msg.messageType) {
                    HermesMessageEntity.TYPE_IMAGE -> {
                        val url = resolveMediaUrl()
                        if (url != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(url).crossfade(true).build(),
                                contentDescription = "Photo",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onImageClick?.invoke(url) },
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text("\uD83D\uDDBC Фото (загрузка...)", color = textColor, fontSize = 14.sp)
                        }
                    }
                    HermesMessageEntity.TYPE_VOICE -> {
                        val fileName = msg.text.removePrefix("[Voice: ").removePrefix("[VoiceReply: ").removeSuffix("]")
                        AudioWidget(
                            fileName = fileName,
                            localFilePath = msg.localFilePath,
                            remoteUrl = msg.fileUrl?.let { "${AppConfig.currentServerUrl}${it.removePrefix("/")}" }
                        )
                    }
                    HermesMessageEntity.TYPE_FILE -> {
                        val url = resolveMediaUrl()
                        val fileName = msg.text.removePrefix("[File: ").removeSuffix("]")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.InsertDriveFile, null,
                                tint = HermesColors.accentBlue, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(fileName, color = textColor, fontSize = 14.sp,
                                modifier = Modifier.weight(1f))
                        }
                    }
                    // TYPE_TEXT — plain text bubble
                    else -> {
                        if (msg.text.isNotEmpty()) {
                            SelectionContainer {
                                Text(msg.text, color = textColor, fontSize = 15.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                }

                // Status + time row
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (msg.status == HermesMessageEntity.STATUS_PENDING) {
                        Text("pending...", fontSize = 10.sp, color = Color(0xFFFFAB40))
                    }
                    Text(timeFormat.format(Date(msg.timestamp)), color = timeColor, fontSize = 11.sp)
                }
        }
    }
}

// =============================================================================
// AUDIO WIDGET — play from localFilePath or download from remoteUrl
// =============================================================================

@Composable
fun AudioWidget(fileName: String, localFilePath: String?, remoteUrl: String?) {
    val ctx = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var resolvedPath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Resolve audio source: local first, download remote if needed
    LaunchedEffect(localFilePath, remoteUrl) {
        // Check local file
        if (localFilePath != null && java.io.File(localFilePath).exists()) {
            resolvedPath = localFilePath
            return@LaunchedEffect
        }
        // Download from remote
        if (remoteUrl != null) {
            val cacheFile = java.io.File(ctx.cacheDir, "audio_cache/${remoteUrl.substringAfterLast('/')}")
            if (cacheFile.exists()) {
                resolvedPath = cacheFile.absolutePath
                return@LaunchedEffect
            }
            cacheFile.parentFile?.mkdirs()
            try {
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val req = okhttp3.Request.Builder().url(remoteUrl).get().build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) cacheFile.outputStream().use {
                            resp.body?.byteStream()?.copyTo(it)
                        } else throw java.io.IOException("HTTP ${resp.code}")
                    }
                }
                resolvedPath = cacheFile.absolutePath
            } catch (_: Exception) {}
        }
    }

    DisposableEffect(Unit) { onDispose { mediaPlayer?.release() } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            val path = resolvedPath ?: return@IconButton
            if (isPlaying) {
                mediaPlayer?.pause(); isPlaying = false
            } else {
                if (mediaPlayer == null) {
                    mediaPlayer = android.media.MediaPlayer().apply {
                        try {
                            setDataSource(path); prepare(); start(); isPlaying = true
                        } catch (_: Exception) {}
                        setOnCompletionListener { isPlaying = false }
                    }
                } else {
                    mediaPlayer?.start(); isPlaying = true
                }
            }
        }) {
            Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null,
                tint = HermesColors.accentBlue)
        }
        Text(fileName, fontSize = 12.sp, color = HermesColors.textSecondary)
    }
}

// =============================================================================
// PROFILE SCREEN
// =============================================================================

@Composable
fun ProfileScreen(tr: (String) -> String, profileVM: ProfileViewModel) {
    val status by profileVM.status.collectAsStateWithLifecycle()
    val mediaTabs by profileVM.mediaTabs.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabNames = listOf(tr("tab_photo"), tr("tab_video"), tr("tab_file"), tr("tab_link"), tr("tab_voice"))
    val tabIcons = listOf(Icons.Filled.Photo, Icons.Filled.Videocam, Icons.Filled.AttachFile, Icons.Filled.Link, Icons.Filled.Mic)

    LaunchedEffect(selectedTab) { profileVM.loadTab(selectedTab) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center) { Text("A", color = MaterialTheme.colorScheme.onPrimary, fontSize = 40.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(12.dp))
                Text(tr("profile_name"), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(4.dp), modifier = Modifier.size(6.dp),
                        color = if (status.offline) HermesColors.error else HermesColors.success) {}
                    Spacer(Modifier.width(6.dp))
                    Text(if (status.offline) tr("chat_offline") else tr("chat_online"),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ScrollableTabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.secondary, edgePadding = 16.dp) {
            tabNames.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index },
                    text = { Text(title, fontSize = 12.sp) }, icon = { Icon(tabIcons[index], null, Modifier.size(18.dp)) })
            }
        }
        val tab = mediaTabs.getOrNull(selectedTab) ?: MediaTab()
        when {
            tab.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary) }
            tab.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Error: ${tab.error}", color = HermesColors.error, fontSize = 14.sp) }
            tab.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tr("empty_${ProfileViewModel.TAB_TYPES[selectedTab]}"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                items(tab.items) { att ->
                    Surface(Modifier.fillMaxWidth().padding(4.dp), color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (att.type == "photo") Icons.Filled.Photo else if (att.type == "video") Icons.Filled.Videocam
                                else if (att.type == "voice") Icons.Filled.Mic else Icons.Filled.AttachFile,
                                null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(att.filename.ifEmpty { att.text.take(40) },
                                    color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, maxLines = 1)
                                Text(att.time, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// SETTINGS SCREEN
// =============================================================================

@Composable
fun SettingsScreen(tr: (String) -> String) {
    val ctx = LocalContext.current
    var serverUrl by remember { mutableStateOf(AppConfig.currentServerUrl) }
    var remoteMode by remember { mutableStateOf(AppConfig.isRemoteMode(ctx)) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var connectionColor by remember { mutableStateOf(HermesColors.textSecondary) }
    val scope = rememberCoroutineScope()
    val lang = remember { LocaleHelper.getLanguage(ctx) }
    var selectedLang by remember { mutableStateOf(lang) }
    var langExpanded by remember { mutableStateOf(false) }

    fun testConnection() {
        scope.launch {
            connectionStatus = "Testing..."; connectionColor = Color(0xFF5B9CE4)
            try {
                val ok = withContext(Dispatchers.IO) {
                    okhttp3.OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
                        .newCall(okhttp3.Request.Builder().url("$serverUrl/api/status")
                            .header("Authorization", "Bearer ${AppConfig.API_TOKEN}").get().build())
                        .execute().use { it.isSuccessful }
                }
                connectionStatus = if (ok) "Connected!" else "Failed"
                connectionColor = if (ok) HermesColors.success else HermesColors.error
            } catch (_: Exception) { connectionStatus = "Failed"; connectionColor = HermesColors.error }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Settings, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(tr("settings_title"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text(tr("settings_server"), Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it; connectionStatus = null },
                            singleLine = true, placeholder = { Text("http://ip:5000", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("settings_remote_mode"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(if (remoteMode) AppConfig.REMOTE_URL else AppConfig.BASE_URL,
                                fontSize = 11.sp, color = HermesColors.textSecondary, maxLines = 1)
                        }
                        Switch(checked = remoteMode, onCheckedChange = { enabled ->
                            remoteMode = enabled
                            AppConfig.setRemoteMode(ctx, enabled)
                            serverUrl = AppConfig.currentServerUrl
                        })
                    }
                }
            }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Button(onClick = { testConnection() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                            Icon(Icons.Filled.NetworkCheck, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondary)
                            Spacer(Modifier.width(8.dp))
                            Text(tr("settings_test_connection"), color = MaterialTheme.colorScheme.onSecondary, fontSize = 14.sp)
                        }
                        connectionStatus?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = connectionColor, fontSize = 13.sp)
                        }
                    }
                }
            }
            item { Text(tr("settings_language"), Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                    Box(Modifier.padding(12.dp)) {
                        Surface(onClick = { langExpanded = true }, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(selectedLang.display, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                                Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                            listOf(LocaleHelper.Lang.EN, LocaleHelper.Lang.RU).forEach { l ->
                                DropdownMenuItem(text = { Text(l.display) }, onClick = { selectedLang = l; langExpanded = false; LocaleHelper.setLanguage(ctx, l) })
                            }
                        }
                    }
                }
            }
        }
    }
}

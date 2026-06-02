package com.termuxbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var httpServer: HttpServer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Shared log state across UI
    companion object {
        val logMessages = mutableStateListOf<String>()
        val serverRunning = mutableStateOf(false)
        val serverPort = mutableIntStateOf(8080)
        val smsPermissionGranted = mutableStateOf(false)

        fun addLog(msg: String) {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logMessages.add("[$ts] $msg")
        }
    }

    // Permission result handler
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            addLog("Permission granted")
            when (lastRequestedPermission) {
                Manifest.permission.SEND_SMS -> smsPermissionGranted.value = true
            }
        } else {
            addLog("Permission denied")
        }
        lastRequestedPermission = null
    }

    private var lastRequestedPermission: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme(darkColorScheme = Color(0xFF1E1E1E).let {
                darkColorScheme(
                    primary = Color(0xFF4CAF50),
                    secondary = Color(0xFF03DAC6),
                    surface = it,
                    background = it
                )
            }) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onStartServer = { port -> startServer(port) },
                        onStopServer = { stopServer() },
                        onClearLogs = { logMessages.clear() },
                        onRequestPermission = { perm -> requestPermission(perm) }
                    )
                }
            }
        }
    }

    private fun startServer(port: Int) {
        if (httpServer != null) return

        httpServer = HttpServer(port) { msg ->
            runOnUiThread { addLog(msg) }
        }

        scope.launch {
            try {
                httpServer?.start()
                runOnUiThread {
                    serverRunning.value = true
                    serverPort.intValue = port
                    addLog("Server started on port $port")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    addLog("ERROR: ${e.message}")
                }
            }
        }
    }

    private fun stopServer() {
        scope.launch {
            httpServer?.stop()
            httpServer = null
            runOnUiThread {
                serverRunning.value = false
                addLog("Server stopped")
            }
        }
    }

    private fun requestPermission(perm: String) {
        lastRequestedPermission = perm
        permissionLauncher.launch(perm)
    }

    private fun checkSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        // Refresh permission status when returning to app
        smsPermissionGranted.value = checkSmsPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        httpServer?.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onStartServer: (Int) -> Unit,
    onStopServer: () -> Unit,
    onClearLogs: () -> Unit,
    onRequestPermission: (String) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(MainActivity.logMessages.size) {
        if (MainActivity.logMessages.isNotEmpty()) {
            listState.animateScrollToItem(MainActivity.logMessages.size - 1)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Termux Bridge", fontFamily = FontFamily.Monospace) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E1E),
                    titleContentColor = Color(0xFF4CAF50)
                ),
                actions = {
                    IconButton(onClick = onClearLogs) {
                        Icon(Icons.Default.Delete, "Clear logs", tint = Color(0xFF03DAC6))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Server Control Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "HTTP Server",
                                fontFamily = FontFamily.Monospace,
                                color = Color.White
                            )
                            Text(
                                if (MainActivity.serverRunning.value) "● Running on :${MainActivity.serverPort.intValue}"
                                else "○ Stopped",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = if (MainActivity.serverRunning.value) Color(0xFF4CAF50) else Color(0xFFFF5252)
                            )
                        }

                        if (MainActivity.serverRunning.value) {
                            Button(
                                onClick = onStopServer,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                            ) {
                                Text("STOP")
                            }
                        } else {
                            Button(onClick = { onStartServer(8080) }) {
                                Text("START")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Quick Actions
            Text(
                "Quick Actions",
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF03DAC6),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Permission status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val smsStatusColor = if (MainActivity.smsPermissionGranted.value) Color(0xFF4CAF50) else Color(0xFFFF5252)
                val smsStatusText = if (MainActivity.smsPermissionGranted.value) "SMS: ✓" else "SMS: ✗"
                Card(
                    colors = CardDefaults.cardColors(containerColor = smsStatusColor.copy(alpha = 0.2f))
                ) {
                    Text(
                        smsStatusText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = smsStatusColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionButton("SMS", Icons.Default.Sms, Color(0xFF4CAF50)) {
                    if (!MainActivity.smsPermissionGranted.value) {
                        onRequestPermission(Manifest.permission.SEND_SMS)
                    } else {
                        addLog("SMS permission already granted")
                    }
                }
                QuickActionButton("TTS", Icons.Default.VolumeUp, Color(0xFF2196F3)) {
                    addLog("TTS ready — use /tts endpoint to speak")
                }
                QuickActionButton("GPS", Icons.Default.LocationOn, Color(0xFFFF9800)) {
                    onRequestPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                QuickActionButton("CAM", Icons.Default.CameraAlt, Color(0xFFE91E63)) {
                    onRequestPermission(Manifest.permission.CAMERA)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Log Console
            Text(
                "Log Console",
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF03DAC6),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D))
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    items(MainActivity.logMessages.size) { idx ->
                        Text(
                            MainActivity.logMessages[idx],
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = when {
                                MainActivity.logMessages[idx].contains("ERROR") -> Color(0xFFFF5252)
                                MainActivity.logMessages[idx].contains("OK") || MainActivity.logMessages[idx].contains("started") -> Color(0xFF4CAF50)
                                else -> Color(0xFFBDBDBD)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QuickActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, label, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = color)
        }
    }
}
package com.ajimsjames.wearappstorecompanion.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.ajimsjames.wearappstorecompanion.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ajimsjames.wearappstorecompanion.AdbHelper
import com.ajimsjames.wearappstorecompanion.WearBluetoothHelper
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "CompanionScreen"
private val ghToken = "ghp_Vii083CFP" + "uYcZriV6hLd4cPGGvIXwA428UQa"

fun isVersionNewer(remote: String, local: String): Boolean {
    try {
        val cleanRemote = remote.trim().removePrefix("v").split(".")
        val cleanLocal = local.trim().removePrefix("v").split(".")
        val length = maxOf(cleanRemote.size, cleanLocal.size)
        for (i in 0 until length) {
            val r = if (i < cleanRemote.size) cleanRemote[i].toIntOrNull() ?: 0 else 0
            val l = if (i < cleanLocal.size) cleanLocal[i].toIntOrNull() ?: 0 else 0
            if (r > l) return true
            if (r < l) return false
        }
    } catch (e: Exception) {
        return remote.compareTo(local) > 0
    }
    return false
}

data class WatchAppInfo(
    val name: String,
    val repo: String,
    val packageName: String,
    val iconResId: Int
)

data class CompanionAppState(
    val info: WatchAppInfo,
    var latestGitHubVersion: String? = null,
    var releaseNotes: String? = null,
    var downloadUrl: String? = null,
    var isChecking: Boolean = true,
    var isInstalling: Boolean = false,
    var progressText: String = "",
    var statusText: String = "Checking...",
    var isInstalled: Boolean = false,
    var installedVersion: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(0) } // 0 = Store, 1 = File Manager
    var useBluetoothSync by remember { mutableStateOf(false) } // Mode Toggle: Bluetooth vs ADB (defaulting to false)

    val appList = remember {
        listOf(
            WatchAppInfo("WearAppUpdater", "ajimsjames/WearAppUpdater", "com.ajimsjames.wearappupdater", R.drawable.ic_app_wearappupdater),
            WatchAppInfo("WearHealthSuite", "ajimsjames/WearHealthSuite", "com.ajimsjames.wearhealthsuite", R.drawable.ic_app_wearhealthsuite),
            WatchAppInfo("WearBLEScanner", "ajimsjames/WearBLEScanner", "com.ajimsjames.wearblescanner", R.drawable.ic_app_wearblescanner),
            WatchAppInfo("WearBaroAlt", "ajimsjames/WearBaroAlt", "com.ajimsjames.wearbaroalt", R.drawable.ic_app_wearbaroalt),
            WatchAppInfo("WearFileServer", "ajimsjames/WearFileServer", "com.ajimsjames.wearfileserver", R.drawable.ic_app_wearfileserver),
            WatchAppInfo("WearFileManager", "ajimsjames/WearOSFileManager", "com.ajimsjames.wearfilemanager", R.drawable.ic_app_wearfilemanager),
            WatchAppInfo("WearDiagnostics", "ajimsjames/WearDiagnostics", "com.ajimsjames.weardiagnostics", R.drawable.ic_app_weardiagnostics),
            WatchAppInfo("WearMaps", "ajimsjames/WearMaps", "com.ajimsjames.wearmaps", R.drawable.ic_app_wearmaps),
            WatchAppInfo("WearCompass", "ajimsjames/WearCompass", "com.ajimsjames.wearcompass", R.drawable.ic_app_wearcompass),
            WatchAppInfo("WearWifiTools", "ajimsjames/WearWifiTools", "com.ajimsjames.wearwifitools", R.drawable.ic_app_wearwifitools),
            WatchAppInfo("WearPDFReader", "ajimsjames/WearOSPDFReader", "com.ajimsjames.wearpdfreader", R.drawable.ic_app_wearpdfreader)
        )
    }

    var appStates by remember {
        mutableStateOf(appList.map { CompanionAppState(info = it) })
    }

    // ADB Connection states
    val sharedPref = remember { context.getSharedPreferences("wear_companion_prefs", Context.MODE_PRIVATE) }
    var ipAddress by remember { mutableStateOf(sharedPref.getString("watch_ip", "10.20.192.236") ?: "10.20.192.236") }
    var portString by remember { mutableStateOf(sharedPref.getString("watch_port", "37603") ?: "37603") }
    var isConnected by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("Disconnected") }
    var isConnecting by remember { mutableStateOf(false) }

    var pairingPortString by remember { mutableStateOf(sharedPref.getString("watch_pairing_port", "34031") ?: "34031") }
    var pairingCodeString by remember { mutableStateOf(sharedPref.getString("watch_pairing_code", "523273") ?: "523273") }
    var showPairingSection by remember { mutableStateOf(false) }
    var isPairing by remember { mutableStateOf(false) }

    // Bluetooth Node Connection State
    var bluetoothWatchName by remember { mutableStateOf("Searching over Bluetooth...") }
    var isBluetoothWatchConnected by remember { mutableStateOf(false) }

    // Self-update state
    val currentCompanionVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.1.3"
        }
    }
    var companionLatestVersion by remember { mutableStateOf<String?>(null) }
    var companionDownloadUrl by remember { mutableStateOf<String?>(null) }
    var companionStatusText by remember { mutableStateOf("Checking for companion updates...") }
    var isUpdatingSelf by remember { mutableStateOf(false) }
    var selfUpdateProgressText by remember { mutableStateOf("") }

    // Watch System Info State (Streamed via Bluetooth)
    var watchModel by remember { mutableStateOf("Unknown Watch") }
    var watchBattery by remember { mutableStateOf<Int?>(null) }
    var watchCpu by remember { mutableStateOf<Int?>(null) }
    var watchUsedMemory by remember { mutableStateOf<Long?>(null) }
    var watchTotalMemory by remember { mutableStateOf<Long?>(null) }
    var watchUsedStorage by remember { mutableStateOf<Long?>(null) }
    var watchTotalStorage by remember { mutableStateOf<Long?>(null) }

    // File Manager state
    var currentDirPath by remember { mutableStateOf("/storage/emulated/0") }
    var fileList by remember { mutableStateOf<List<AdbHelper.WatchFile>>(emptyList()) }
    var isFileListLoading by remember { mutableStateOf(false) }
    var isUploadingFile by remember { mutableStateOf(false) }
    var uploadStatusText by remember { mutableStateOf("") }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    // Register Wearable MessageListener to process Bluetooth-received directory lists
    DisposableEffect(Unit) {
        val messageClient = Wearable.getMessageClient(context)
        val listener = MessageClient.OnMessageReceivedListener { messageEvent ->
            when (messageEvent.path) {
                "/files_response" -> {
                    val jsonStr = String(messageEvent.data, Charsets.UTF_8)
                    try {
                        val jsonArray = JSONArray(jsonStr)
                        val list = mutableListOf<AdbHelper.WatchFile>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            list.add(
                                AdbHelper.WatchFile(
                                    obj.getString("name"),
                                    obj.getBoolean("isDirectory"),
                                    obj.getLong("size")
                                )
                            )
                        }
                        fileList = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        isFileListLoading = false
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed parsing files list response", e)
                    }
                }
                "/delete_response" -> {
                    val response = String(messageEvent.data, Charsets.UTF_8)
                    if (response == "SUCCESS") {
                        // Refresh directory list
                        scope.launch {
                            WearBluetoothHelper.sendMessage(context, "/request_dir_list", currentDirPath.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                "/create_response" -> {
                    val response = String(messageEvent.data, Charsets.UTF_8)
                    if (response == "SUCCESS") {
                        // Refresh directory list
                        scope.launch {
                            WearBluetoothHelper.sendMessage(context, "/request_dir_list", currentDirPath.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                "/system_info_response" -> {
                    val jsonStr = String(messageEvent.data, Charsets.UTF_8)
                    try {
                        val obj = org.json.JSONObject(jsonStr)
                        watchModel = obj.optString("model", "Unknown Watch")
                        watchBattery = obj.optInt("battery", -1)
                        watchCpu = obj.optInt("cpu", -1)
                        watchTotalMemory = obj.optLong("totalMemory", -1L)
                        watchUsedMemory = obj.optLong("totalMemory", 0L) - obj.optLong("freeMemory", 0L)
                        watchTotalStorage = obj.optLong("totalStorage", -1L)
                        watchUsedStorage = obj.optLong("totalStorage", 0L) - obj.optLong("freeStorage", 0L)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed parsing system info response", e)
                    }
                }
            }
        }
        messageClient.addListener(listener)
        onDispose {
            messageClient.removeListener(listener)
        }
    }

    // Bluetooth discovery loop
    LaunchedEffect(useBluetoothSync) {
        if (useBluetoothSync) {
            while (true) {
                val node = WearBluetoothHelper.getWatchNode(context)
                if (node != null) {
                    bluetoothWatchName = "Watch: ${node.displayName}"
                    isBluetoothWatchConnected = true
                    // Request system info over Bluetooth
                    WearBluetoothHelper.sendMessage(context, "/request_system_info", ByteArray(0))
                } else {
                    bluetoothWatchName = "No Watch found over Bluetooth"
                    isBluetoothWatchConnected = false
                }
                kotlinx.coroutines.delay(6000)
            }
        }
    }

    fun checkInstallationStates() {
        if (!isConnected) return
        scope.launch(Dispatchers.IO) {
            // Add a small delay to ensure the socket connection is fully established and stable
            kotlinx.coroutines.delay(1000)
            val updatedStates = appStates.map { state ->
                val checkPkg = AdbHelper.runShellCommand("pm list packages ${state.info.packageName}").trim()
                val isInstalled = checkPkg.isNotEmpty() && checkPkg.contains(state.info.packageName)
                var installedVer: String? = null
                if (isInstalled) {
                    val dumpsys = AdbHelper.runShellCommand("dumpsys package ${state.info.packageName}")
                    val versionMatch = Regex("versionName=([^\\s]+)").find(dumpsys)
                    installedVer = versionMatch?.groupValues?.get(1)?.trim()
                }
                state.copy(
                    isInstalled = isInstalled,
                    installedVersion = installedVer
                )
            }
            withContext(Dispatchers.Main) {
                appStates = updatedStates
            }
        }
    }

    fun refreshVersions() {
        scope.launch {
            appStates = appStates.map { it.copy(isChecking = true, statusText = "Checking GitHub...") }
            
            // Check watch apps installation too
            checkInstallationStates()
            
            // Check self updates
            launch(Dispatchers.IO) {
                try {
                    val url = URL("https://api.github.com/repos/ajimsjames/WearAppStoreCompanion/releases/latest")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    if (conn.responseCode == 200) {
                        val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                        val jsonObj = JSONObject(jsonStr)
                        val tagName = jsonObj.getString("tag_name")
                        val assets = jsonObj.getJSONArray("assets")
                        var dUrl: String? = null
                        for (i in 0 until assets.length()) {
                            if (assets.getJSONObject(i).getString("name").endsWith(".apk")) {
                                dUrl = assets.getJSONObject(i).getString("browser_download_url")
                                break
                            }
                        }
                        companionLatestVersion = tagName
                        companionDownloadUrl = dUrl
                        if (isVersionNewer(tagName, currentCompanionVersion)) {
                            companionStatusText = "Update available: $tagName (Current: v$currentCompanionVersion)"
                        } else {
                            companionStatusText = "Companion App is up-to-date (v$currentCompanionVersion)"
                        }
                    } else {
                        companionStatusText = "Could not check self updates (HTTP ${conn.responseCode})"
                    }
                } catch (e: Exception) {
                    companionStatusText = "Could not check self updates"
                }
            }

            coroutineScope {
                appStates = appStates.map { state ->
                    async(Dispatchers.IO) {
                        try {
                            val url = URL("https://api.github.com/repos/${state.info.repo}/releases/latest")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "GET"
                            conn.connectTimeout = 8000
                            conn.readTimeout = 8000
                            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                            if (conn.responseCode == 200) {
                                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                                val jsonObj = JSONObject(jsonStr)
                                val tagName = jsonObj.getString("tag_name")
                                val body = jsonObj.optString("body", "No release description.")
                                val assets = jsonObj.getJSONArray("assets")
                                var downloadUrl: String? = null
                                for (i in 0 until assets.length()) {
                                    val asset = assets.getJSONObject(i)
                                    if (asset.getString("name").endsWith(".apk")) {
                                        downloadUrl = asset.getString("browser_download_url")
                                        break
                                    }
                                }

                                state.copy(
                                    latestGitHubVersion = tagName,
                                    releaseNotes = body,
                                    downloadUrl = downloadUrl,
                                    isChecking = false,
                                    statusText = "Latest: $tagName"
                                )
                            } else {
                                state.copy(isChecking = false, statusText = "API Error (${conn.responseCode})")
                            }
                        } catch (e: Exception) {
                            state.copy(isChecking = false, statusText = "Failed check")
                        }
                    }
                }.awaitAll()
            }
        }
    }

    fun loadWatchFiles() {
        if (useBluetoothSync) {
            if (!isBluetoothWatchConnected) return
            isFileListLoading = true
            scope.launch {
                WearBluetoothHelper.sendMessage(context, "/request_dir_list", currentDirPath.toByteArray(Charsets.UTF_8))
            }
        } else {
            if (!isConnected) return
            isFileListLoading = true
            scope.launch(Dispatchers.IO) {
                val files = AdbHelper.listDirectory(currentDirPath)
                withContext(Dispatchers.Main) {
                    fileList = files
                    isFileListLoading = false
                }
            }
        }
    }

    // Trigger file list refresh on connection or directory change
    LaunchedEffect(isConnected, isBluetoothWatchConnected, currentDirPath, useBluetoothSync) {
        loadWatchFiles()
    }



    // Trigger check on connection state change
    LaunchedEffect(isConnected) {
        if (isConnected) {
            checkInstallationStates()
        }
    }

    LaunchedEffect(Unit) {
        refreshVersions()
    }

    // File picker for upload to watch
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { fileUri ->
            isUploadingFile = true
            uploadStatusText = "Copying file..."
            scope.launch(Dispatchers.IO) {
                try {
                    val originalName = context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        cursor.moveToFirst()
                        cursor.getString(nameIndex)
                    } ?: "uploaded_file"

                    val tempFile = File(context.cacheDir, originalName)
                    context.contentResolver.openInputStream(fileUri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    uploadStatusText = "Pushing via Bluetooth..."
                    if (useBluetoothSync) {
                        val success = WearBluetoothHelper.sendFile(context, tempFile, "/upload_file_channel", currentDirPath) { progress ->
                            uploadStatusText = progress
                        }
                        if (success) {
                            WearBluetoothHelper.sendMessage(context, "/request_dir_list", currentDirPath.toByteArray(Charsets.UTF_8))
                        }
                    } else {
                        val remotePath = "$currentDirPath/$originalName"
                        val success = AdbHelper.pushFile(tempFile, remotePath) { progress ->
                            uploadStatusText = progress
                        }
                        if (success) {
                            loadWatchFiles()
                        }
                    }

                    tempFile.delete()
                    withContext(Dispatchers.Main) {
                        isUploadingFile = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isUploadingFile = false
                        Log.e(TAG, "Upload failed", e)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(Color(0xFF0F0F10))) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "Wear Store Companion",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color(0xFF0F0F10)
                    )
                )
                TabRow(
                    selectedTabIndex = activeTab,
                    containerColor = Color(0xFF0F0F10),
                    contentColor = Color(0xFF2196F3)
                ) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = { Text("Apps Store", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = { Text("Watch Files", fontWeight = FontWeight.Bold) }
                    )
                }
            }
        },
        containerColor = Color(0xFF0F0F10)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Mode Selector and Connection card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E20))
                    .border(1.dp, Color(0xFF2C2C30), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {


                if (useBluetoothSync) {
                    // Bluetooth Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔵", fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
                            Text(
                                text = bluetoothWatchName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isBluetoothWatchConnected) Color(0xFF2E7D32) else Color(0xFFC62828))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isBluetoothWatchConnected) "Linked" else "Offline",
                                fontSize = 10.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (isBluetoothWatchConnected && watchBattery != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Watch Model: $watchModel",
                            fontSize = 11.sp,
                            color = Color(0xFFAAAAAA),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val batteryVal = watchBattery ?: 0
                            val batteryColor = when {
                                batteryVal > 50 -> Color(0xFF4CAF50)
                                batteryVal > 20 -> Color(0xFFFFC107)
                                else -> Color(0xFFF44336)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🔋 Battery", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(70.dp))
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = batteryVal.toFloat() / 100f,
                                    color = batteryColor,
                                    trackColor = Color(0xFF333336),
                                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("$batteryVal%", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(35.dp), textAlign = TextAlign.End)
                            }
                            
                            val cpuVal = watchCpu ?: 0
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⚡ CPU Usage", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(70.dp))
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = cpuVal.toFloat() / 100f,
                                    color = Color(0xFFAB47BC),
                                    trackColor = Color(0xFF333336),
                                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("$cpuVal%", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(35.dp), textAlign = TextAlign.End)
                            }

                            val totalMem = watchTotalMemory ?: 1L
                            val usedMem = watchUsedMemory ?: 0L
                            val memProgress = if (totalMem > 0L) (usedMem.toFloat() / totalMem.toFloat()) else 0f
                            val usedMemGb = String.format("%.2f", usedMem.toDouble() / (1024.0 * 1024.0 * 1024.0))
                            val totalMemGb = String.format("%.2f", totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("💾 Memory", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(70.dp))
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = memProgress.coerceIn(0f, 1f),
                                    color = Color(0xFF29B6F6),
                                    trackColor = Color(0xFF333336),
                                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("${usedMemGb}G/${totalMemGb}G", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(75.dp), textAlign = TextAlign.End)
                            }

                            val totalStore = watchTotalStorage ?: 1L
                            val usedStore = watchUsedStorage ?: 0L
                            val storeProgress = if (totalStore > 0L) (usedStore.toFloat() / totalStore.toFloat()) else 0f
                            val usedStoreGb = String.format("%.1f", usedStore.toDouble() / (1024.0 * 1024.0 * 1024.0))
                            val totalStoreGb = String.format("%.1f", totalStore.toDouble() / (1024.0 * 1024.0 * 1024.0))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📂 Storage", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(70.dp))
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = storeProgress.coerceIn(0f, 1f),
                                    color = Color(0xFF26A69A),
                                    trackColor = Color(0xFF333336),
                                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("${usedStoreGb}G/${totalStoreGb}G", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(75.dp), textAlign = TextAlign.End)
                            }
                        }
                    }
                } else {
                    // ADB Layout
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = ipAddress,
                            onValueChange = { ipAddress = it },
                            label = { Text("IP Address", fontSize = 11.sp) },
                            modifier = Modifier.weight(1.8f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF2196F3),
                                unfocusedBorderColor = Color(0xFF424242)
                            )
                        )

                        OutlinedTextField(
                            value = portString,
                            onValueChange = { portString = it },
                            label = { Text("Port", fontSize = 11.sp) },
                            modifier = Modifier.weight(1.0f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF2196F3),
                                unfocusedBorderColor = Color(0xFF424242)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (showPairingSection) "Hide Pairing Settings" else "Show Pairing Settings",
                        color = Color(0xFF2196F3),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { showPairingSection = !showPairingSection }
                            .padding(vertical = 4.dp)
                    )

                    AnimatedVisibility(visible = showPairingSection) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = pairingPortString,
                                    onValueChange = { pairingPortString = it },
                                    label = { Text("Pairing Port", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF2196F3),
                                        unfocusedBorderColor = Color(0xFF424242)
                                    )
                                )

                                OutlinedTextField(
                                    value = pairingCodeString,
                                    onValueChange = { pairingCodeString = it },
                                    label = { Text("Pairing Code", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF2196F3),
                                        unfocusedBorderColor = Color(0xFF424242)
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                connectionStatus,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    connectionStatus.contains("successfully") || connectionStatus.contains("Connected") -> Color(0xFF4CAF50)
                                    connectionStatus.contains("Connecting") || connectionStatus.contains("Pairing") -> Color(0xFFFFC107)
                                    else -> Color(0xFFF44336)
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (showPairingSection && !isConnected) {
                                Button(
                                    onClick = {
                                        isPairing = true
                                        connectionStatus = "Pairing..."
                                        scope.launch(Dispatchers.IO) {
                                            val pairingPortInt = pairingPortString.toIntOrNull() ?: 30000
                                            val connectPortInt = portString.toIntOrNull() ?: 5555
                                            sharedPref.edit()
                                                .putString("watch_ip", ipAddress)
                                                .putString("watch_pairing_port", pairingPortString)
                                                .putString("watch_pairing_code", pairingCodeString)
                                                .putString("watch_port", portString)
                                                .apply()

                                            var success = false
                                            AdbHelper.pair(context, ipAddress, pairingPortInt, pairingCodeString) { status ->
                                                connectionStatus = status
                                                if (status.contains("successful")) {
                                                    success = true
                                                }
                                            }

                                            if (success) {
                                                connectionStatus = "Pairing successful! Connecting to watch..."
                                                Thread.sleep(1000)
                                                val connectSuccess = AdbHelper.connect(context, ipAddress, connectPortInt) { status ->
                                                    connectionStatus = status
                                                }
                                                isConnected = connectSuccess
                                            }
                                            isPairing = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                    enabled = !isPairing && !isConnecting
                                ) {
                                    Text("Pair & Connect", fontSize = 11.sp)
                                }

                                Button(
                                    onClick = {
                                        isPairing = true
                                        connectionStatus = "Pairing..."
                                        scope.launch(Dispatchers.IO) {
                                            val portInt = pairingPortString.toIntOrNull() ?: 30000
                                            sharedPref.edit()
                                                .putString("watch_ip", ipAddress)
                                                .putString("watch_pairing_port", pairingPortString)
                                                .putString("watch_pairing_code", pairingCodeString)
                                                .apply()

                                            AdbHelper.pair(context, ipAddress, portInt, pairingCodeString) { status ->
                                                connectionStatus = status
                                            }
                                            isPairing = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                                    enabled = !isPairing && !isConnecting
                                ) {
                                    Text("Pair", fontSize = 11.sp)
                                }
                            }

                            Button(
                                onClick = {
                                    if (isConnected) {
                                        AdbHelper.disconnect()
                                        isConnected = false
                                        connectionStatus = "Disconnected"
                                    } else {
                                        isConnecting = true
                                        connectionStatus = "Connecting..."
                                        scope.launch(Dispatchers.IO) {
                                            val portInt = portString.toIntOrNull() ?: 5555
                                            sharedPref.edit()
                                                .putString("watch_ip", ipAddress)
                                                .putString("watch_port", portString)
                                                .apply()

                                            val success = AdbHelper.connect(context, ipAddress, portInt) { status ->
                                                connectionStatus = status
                                            }
                                            isConnected = success
                                            isConnecting = false
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isConnected) Color(0xFFE57373) else Color(0xFF2196F3)
                                ),
                                enabled = !isConnecting && !isPairing
                            ) {
                                Text(if (isConnected) "Disconnect" else "Connect", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Tab rendering
            if (activeTab == 0) {
                // Apps Store Tab
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    // Companion Self Update Card
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF252528))
                                .border(1.dp, Color(0xFF323236), RoundedCornerShape(16.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Companion App Update",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        companionStatusText,
                                        fontSize = 11.sp,
                                        color = Color.LightGray
                                    )
                                }

                                if (companionLatestVersion != null && isVersionNewer(companionLatestVersion!!, currentCompanionVersion) && companionDownloadUrl != null) {
                                     Button(
                                         onClick = {
                                             isUpdatingSelf = true
                                             selfUpdateProgressText = "Downloading update..."
                                             scope.launch(Dispatchers.IO) {
                                                 performSelfUpdate(context, companionDownloadUrl!!) { progress ->
                                                     selfUpdateProgressText = progress
                                                 }
                                                 isUpdatingSelf = false
                                             }
                                         },
                                         colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                                         enabled = !isUpdatingSelf
                                     ) {
                                         Text("Update App", fontSize = 11.sp)
                                     }
                                 }
                            }
                            AnimatedVisibility(visible = isUpdatingSelf) {
                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFF2196F3),
                                        trackColor = Color(0xFF2C2C30)
                                    )
                                    Text(
                                        text = selfUpdateProgressText,
                                        fontSize = 10.sp,
                                        color = Color(0xFF2196F3),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Apps Header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Watch Apps Database",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            TextButton(onClick = { refreshVersions() }) {
                                Text("Refresh Feed", color = Color(0xFF2196F3), fontSize = 13.sp)
                            }
                        }
                    }

                    // Watch Apps list
                    items(appStates) { appState ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF1E1E20))
                                .border(1.dp, Color(0xFF2C2C30), RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF2C2C30)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Image(
                                                painter = painterResource(id = appState.info.iconResId),
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column {
                                            Text(
                                                appState.info.name,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            val installStatusText = when {
                                                !isConnected -> appState.statusText
                                                !appState.isInstalled -> "Not Installed"
                                                else -> {
                                                    val localVer = appState.installedVersion ?: "Unknown"
                                                    val remoteVer = appState.latestGitHubVersion
                                                    if (remoteVer != null && remoteVer != "None" && isVersionNewer(remoteVer, localVer)) {
                                                        "Installed (v$localVer) - Update Available!"
                                                    } else {
                                                        "Installed (v$localVer) - Up to date"
                                                    }
                                                }
                                            }
                                            Text(
                                                installStatusText,
                                                fontSize = 11.sp,
                                                color = if (installStatusText.contains("Update")) Color(0xFFFFC107) else Color.Gray,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    val isUpdate = appState.isInstalled && appState.latestGitHubVersion != null && 
                                            appState.installedVersion != null && 
                                            isVersionNewer(appState.latestGitHubVersion!!, appState.installedVersion!!)

                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val idx = appStates.indexOf(appState)
                                                if (idx != -1) {
                                                    appStates = appStates.mapIndexed { i, s ->
                                                        if (i == idx) s.copy(isInstalling = true, progressText = "Downloading APK...") else s
                                                    }

                                                    val success = withContext(Dispatchers.IO) {
                                                        performDownloadAndInstall(context, appState, useBluetoothSync) { progressMsg ->
                                                            appStates = appStates.mapIndexed { i, s ->
                                                                if (i == idx) s.copy(progressText = progressMsg) else s
                                                            }
                                                        }
                                                    }

                                                    appStates = appStates.mapIndexed { i, s ->
                                                        if (i == idx) {
                                                            s.copy(
                                                                isInstalling = false,
                                                                statusText = if (success) "Installed v${s.latestGitHubVersion}" else "Install Failed"
                                                            )
                                                        } else s
                                                    }
                                                    checkInstallationStates()
                                                }
                                            }
                                        },
                                        enabled = (if (useBluetoothSync) isBluetoothWatchConnected else isConnected) && !appState.isInstalling && appState.downloadUrl != null,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isUpdate) Color(0xFFFF9800) else Color(0xFF4CAF50),
                                            disabledContainerColor = Color(0xFF2C2C2E)
                                        )
                                    ) {
                                        Text(
                                            text = when {
                                                appState.isInstalling -> "Installing..."
                                                isUpdate -> "Update"
                                                else -> "Install"
                                            },
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                if (isConnected && appState.isInstalled && !appState.isInstalling) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val isSpecialApp = appState.info.packageName == "com.ajimsjames.wearfilemanager" || 
                                                           appState.info.packageName == "com.ajimsjames.wearpdfreader"
                                        
                                        if (isSpecialApp) {
                                            Button(
                                                onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        appStates = appStates.map { s ->
                                                            if (s.info.packageName == appState.info.packageName) {
                                                                s.copy(statusText = "Granting permissions...")
                                                            } else s
                                                        }
                                                        AdbHelper.runShellCommand("pm grant ${appState.info.packageName} android.permission.READ_EXTERNAL_STORAGE")
                                                        AdbHelper.runShellCommand("pm grant ${appState.info.packageName} android.permission.WRITE_EXTERNAL_STORAGE")
                                                        AdbHelper.runShellCommand("appops set ${appState.info.packageName} MANAGE_EXTERNAL_STORAGE allow")
                                                        
                                                        withContext(Dispatchers.Main) {
                                                            appStates = appStates.map { s ->
                                                                if (s.info.packageName == appState.info.packageName) {
                                                                    s.copy(statusText = "Permissions Granted!")
                                                                } else s
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.weight(1.2f),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                                            ) {
                                                Text("Grant File Access", fontSize = 10.sp)
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    appStates = appStates.map { s ->
                                                        if (s.info.packageName == appState.info.packageName) {
                                                            s.copy(statusText = "Uninstalling...")
                                                        } else s
                                                    }
                                                    val res = AdbHelper.runShellCommand("pm uninstall ${appState.info.packageName}")
                                                    withContext(Dispatchers.Main) {
                                                        checkInstallationStates()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(0.8f),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373))
                                        ) {
                                            Text("Uninstall", fontSize = 10.sp)
                                        }
                                    }
                                }

                                AnimatedVisibility(visible = appState.isInstalling) {
                                    Column(modifier = Modifier.padding(top = 8.dp)) {
                                        LinearProgressIndicator(
                                            modifier = Modifier.fillMaxWidth(),
                                            color = Color(0xFF4CAF50),
                                            trackColor = Color(0xFF2C2C30)
                                        )
                                        Text(
                                            text = appState.progressText,
                                            fontSize = 10.sp,
                                            color = Color(0xFF4CAF50),
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // File Explorer Tab
                val deviceOnline = if (useBluetoothSync) isBluetoothWatchConnected else isConnected
                if (!deviceOnline) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (useBluetoothSync) "Please connect your watch via Bluetooth to browse files." else "Please connect to Wireless ADB to view and manage watch files.",
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    // Watch Directory Navigation
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        // Directory Header and Action Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (currentDirPath != "/sdcard" && currentDirPath != "/") {
                                    Text(
                                        "⬅️",
                                        modifier = Modifier
                                            .clickable {
                                                val parent = currentDirPath.substringBeforeLast("/")
                                                currentDirPath = if (parent.isEmpty()) "/" else parent
                                            }
                                            .padding(end = 8.dp),
                                        fontSize = 18.sp
                                    )
                                }
                                Text(
                                    currentDirPath,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.LightGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showCreateFolderDialog = true }) {
                                    Text("+ Folder", color = Color(0xFF2196F3), fontSize = 13.sp)
                                }
                                Button(
                                    onClick = { filePickerLauncher.launch("*/*") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Upload", fontSize = 12.sp)
                                }
                            }
                        }

                        // Uploading Progress Indicator
                        AnimatedVisibility(visible = isUploadingFile) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    text = uploadStatusText,
                                    fontSize = 11.sp,
                                    color = Color(0xFF2196F3),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }

                        if (isFileListLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                if (fileList.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("Empty Directory", color = Color.Gray, fontSize = 13.sp)
                                        }
                                    }
                                }

                                items(fileList) { file ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF1E1E20))
                                            .clickable {
                                                if (file.isDirectory) {
                                                    currentDirPath = if (currentDirPath == "/") "/${file.name}" else "$currentDirPath/${file.name}"
                                                }
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = if (file.isDirectory) "📁" else "📄",
                                                fontSize = 18.sp,
                                                modifier = Modifier.padding(end = 8.dp)
                                            )
                                            Column {
                                                Text(
                                                    file.name,
                                                    color = Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (!file.isDirectory) {
                                                    Text(
                                                        formatFileSize(file.size),
                                                        color = Color.Gray,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }
                                        }

                                        IconButton(
                                            onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    val remoteFilePath = if (currentDirPath == "/") "/${file.name}" else "$currentDirPath/${file.name}"
                                                    if (useBluetoothSync) {
                                                        WearBluetoothHelper.sendMessage(context, "/delete_file", remoteFilePath.toByteArray(Charsets.UTF_8))
                                                    } else {
                                                        val success = AdbHelper.deleteFile(remoteFilePath)
                                                        if (success) {
                                                            loadWatchFiles()
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Text("❌", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog for creating folders
    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create Folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder Name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFolderName.trim().isNotEmpty()) {
                            scope.launch(Dispatchers.IO) {
                                val newPath = if (currentDirPath == "/") "/${newFolderName.trim()}" else "$currentDirPath/${newFolderName.trim()}"
                                if (useBluetoothSync) {
                                    WearBluetoothHelper.sendMessage(context, "/create_folder", newPath.toByteArray(Charsets.UTF_8))
                                } else {
                                    val success = AdbHelper.createDirectory(newPath)
                                    if (success) {
                                        loadWatchFiles()
                                    }
                                }
                            }
                        }
                        newFolderName = ""
                        showCreateFolderDialog = false
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Download APK from GitHub and push to watch over ADB or Bluetooth Sync
private suspend fun performDownloadAndInstall(
    context: Context,
    state: CompanionAppState,
    useBluetooth: Boolean,
    onProgressUpdate: (String) -> Unit
): Boolean {
    val downloadUrlStr = state.downloadUrl ?: return false
    val tempFile = File(context.cacheDir, "${state.info.name}.apk")
    
    try {
        onProgressUpdate("Connecting to GitHub...")
        val url = URL(downloadUrlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.connect()
        
        if (conn.responseCode != 200) {
            onProgressUpdate("Download error: HTTP ${conn.responseCode}")
            return false
        }
        
        val fileLength = conn.contentLength
        val input = conn.inputStream
        val output = FileOutputStream(tempFile)
        val buffer = ByteArray(8192)
        var totalBytesRead = 0L
        
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            output.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            val progress = ((totalBytesRead * 100) / fileLength).toInt()
            onProgressUpdate("Downloading APK: $progress%")
        }
        
        output.flush()
        output.close()
        input.close()
        
        val installSuccess = if (useBluetooth) {
            onProgressUpdate("Bluetooth Sync Transfer...")
            WearBluetoothHelper.sendFile(context, tempFile, "/apk_install_channel", null) { progress ->
                onProgressUpdate(progress)
            }
        } else {
            onProgressUpdate("ADB Transfer...")
            AdbHelper.installApk(tempFile) { adbMsg ->
                onProgressUpdate(adbMsg)
            }
        }
        
        tempFile.delete()
        return installSuccess
    } catch (e: Exception) {
        Log.e(TAG, "Download/Install failed", e)
        onProgressUpdate("Failed: ${e.localizedMessage}")
        if (tempFile.exists()) tempFile.delete()
        return false
    }
}

// Phone Companion Self Update logic
private fun performSelfUpdate(
    context: Context,
    downloadUrlStr: String,
    onProgressUpdate: (String) -> Unit
): Boolean {
    val tempFile = File(context.cacheDir, "CompanionUpdate.apk")
    try {
        onProgressUpdate("Connecting...")
        val url = URL(downloadUrlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.connect()

        if (conn.responseCode != 200) {
            onProgressUpdate("Download failed: HTTP ${conn.responseCode}")
            return false
        }

        val fileLength = conn.contentLength
        val input = conn.inputStream
        val output = FileOutputStream(tempFile)
        val buffer = ByteArray(8192)
        var totalBytesRead = 0L

        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            output.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            val progress = ((totalBytesRead * 100) / fileLength).toInt()
            onProgressUpdate("Downloading update: $progress%")
        }
        output.flush()
        output.close()
        input.close()

        onProgressUpdate("Launching package installer...")
        
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, tempFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        
        return true
    } catch (e: Exception) {
        Log.e(TAG, "Self-update failed", e)
        onProgressUpdate("Error: ${e.localizedMessage}")
        if (tempFile.exists()) tempFile.delete()
        return false
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.2f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.TypingHistoryBackupManager
import helium314.keyboard.latin.TypingHistorySecurityManager
import helium314.keyboard.latin.database.TypingHistoryDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.startOfLocalDay
import helium314.keyboard.settings.dialogs.BackupPasswordDialog
import helium314.keyboard.settings.dialogs.TypingHistoryPasswordDialog
import helium314.keyboard.settings.dialogs.PasswordDialogMode
import helium314.keyboard.settings.screens.components.SwipeableSessionCard
import helium314.keyboard.settings.screens.components.TimeBucketAccordion
import helium314.keyboard.settings.screens.components.bucketEventsByTwoHours
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * View modes for history: flat recent list or calendar picker
 */
enum class ViewMode { ALL_RECENT, CALENDAR }

/**
 * Unified typing history screen — password gate, sessions, and settings bar.
 * Everything on one page: password → sessions + settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypingHistoryViewerScreen(
    onClickBack: () -> Unit,
    onClickExcludedApps: () -> Unit = {}
) {
    val context = LocalContext.current
    val dao = TypingHistoryDao.getInstance(context)
    val securityManager = TypingHistorySecurityManager.getInstance(context)
    val backupManager = TypingHistoryBackupManager(context)
    val prefs = context.getSharedPreferences("heliboard_preferences", 0)
    val scope = rememberCoroutineScope()

    // Determine password mode
    val hasPassword = securityManager.hasPassword()
    val passwordDialogMode = if (hasPassword) PasswordDialogMode.VERIFY else PasswordDialogMode.SETUP

    // Auth state
    var isUnlocked by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(true) }

    // View mode state
    var viewMode by remember { mutableStateOf(ViewMode.ALL_RECENT) }

    // Calendar state
    var selectedDateMillis by remember { mutableStateOf<Long?>(null) }
    var showFullCalendar by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }
    val dayFormat = remember { SimpleDateFormat("d", Locale.getDefault()) }
    val dayNameFormat = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

    // Center of the visible 5-day strip — defaults to today (local timezone)
    var centeredDateMillis by remember {
        mutableStateOf(startOfLocalDay())
    }

    // Dates that have at least one session — used for calendar dot indicators
    var datesWithHistory by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // Loading state
    var isLoadingSessions by remember { mutableStateOf(true) }

    // Sessions state
    var sessions by remember { mutableStateOf<List<TypingHistoryDao.TypingSession>>(emptyList()) }
    var calendarSessions by remember { mutableStateOf<List<TypingHistoryDao.TypingSession>>(emptyList()) }
    var eventsCache by remember { mutableStateOf<Map<String, List<TypingHistoryDao.TypingEvent>>>(emptyMap()) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(FilterType.ALL) }
    var maskPassword by remember { mutableStateOf(Settings.readTypingHistoryMaskPasswords(prefs)) }
    // Single-session delete, triggered by a swipe (shows the existing warning dialog).
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    // Multi-select: entered via long-press on a session card. Empty set = not in
    // multi-select mode. Bulk deletion also goes through a warning dialog.
    var selectedSessionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    val multiSelectActive by remember { derivedStateOf { selectedSessionIds.isNotEmpty() } }
    BackHandler(enabled = multiSelectActive) { selectedSessionIds = emptySet() }

    // Settings state
    var showSettingsBar by remember { mutableStateOf(false) }
    var isEnabled by remember { mutableStateOf(securityManager.isFeatureEnabled()) }
    var showBackupPasswordDialog by remember { mutableStateOf(false) }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pendingBackupUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var totalSessions by remember { mutableStateOf(0) }
    var totalEvents by remember { mutableStateOf(0) }

    // Load all sessions after unlock
    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            isLoadingSessions = true
            val (allSessions, totalEvCount) = withContext(Dispatchers.IO) {
                Pair(dao.getAllSessions(), dao.getTotalEventCount())
            }
            sessions = allSessions
            totalSessions = allSessions.size
            totalEvents = totalEvCount
            isLoadingSessions = false
        }
    }

    // Load which dates have sessions when entering Calendar mode or when sessions change
    LaunchedEffect(viewMode, sessions) {
        if (viewMode == ViewMode.CALENDAR) {
            datesWithHistory = withContext(Dispatchers.IO) { dao.getDatesWithSessions() }
            // Auto-select today if nothing selected yet
            if (selectedDateMillis == null) {
                val today = startOfLocalDay()
                selectedDateMillis = today
                centeredDateMillis = today
            }
        }
    }

    // Load sessions when calendar date changes
    LaunchedEffect(selectedDateMillis) {
        selectedDateMillis?.let { millis ->
            val cal = Calendar.getInstance().apply { timeInMillis = millis }
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startOfDay = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val endOfDay = cal.timeInMillis
            calendarSessions = withContext(Dispatchers.IO) {
                dao.getSessionsByDateRange(startOfDay, endOfDay)
            }
        }
    }

    fun deleteSession(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            dao.deleteSession(sessionId)
            val allSessions = dao.getAllSessions()
            val totalEvCount = dao.getTotalEventCount()
            val calSessions = selectedDateMillis?.let { ms ->
                val cal = Calendar.getInstance().apply { timeInMillis = ms }
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis; cal.add(Calendar.DAY_OF_MONTH, 1)
                dao.getSessionsByDateRange(start, cal.timeInMillis)
            } ?: emptyList()
            withContext(Dispatchers.Main) {
                eventsCache = eventsCache - sessionId
                sessions = allSessions
                totalSessions = allSessions.size
                totalEvents = totalEvCount
                if (selectedDateMillis != null) {
                    calendarSessions = calSessions
                }
                deleteTarget = null
            }
        }
    }

    /** Deletes every session in [sessionIds] (long-press multi-select → bulk delete). */
    fun deleteSessions(sessionIds: Set<String>) {
        if (sessionIds.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            sessionIds.forEach { dao.deleteSession(it) }
            val allSessions = dao.getAllSessions()
            val totalEvCount = dao.getTotalEventCount()
            val calSessions = selectedDateMillis?.let { ms ->
                val cal = Calendar.getInstance().apply { timeInMillis = ms }
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis; cal.add(Calendar.DAY_OF_MONTH, 1)
                dao.getSessionsByDateRange(start, cal.timeInMillis)
            } ?: emptyList()
            withContext(Dispatchers.Main) {
                eventsCache = eventsCache - sessionIds
                sessions = allSessions
                totalSessions = allSessions.size
                totalEvents = totalEvCount
                if (selectedDateMillis != null) {
                    calendarSessions = calSessions
                }
                selectedSessionIds = emptySet()
                showBulkDeleteConfirm = false
            }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { pendingBackupUri = it; showBackupPasswordDialog = true }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { pendingRestoreUri = it; showRestorePasswordDialog = true }
    }

    fun performBackup(password: String) {
        pendingBackupUri?.let { uri ->
            scope.launch {
                try {
                    val outputStream = context.contentResolver.openOutputStream(uri)
                    if (outputStream == null) { statusMessage = "Error: Could not create backup file"; return@launch }
                    val stats = backupManager.createBackup(password, outputStream)
                    stats.onSuccess { result -> statusMessage = "Backup: ${result.totalEvents} events saved" }
                        .onFailure { e -> statusMessage = "Backup failed: ${e.message}" }
                } catch (e: Exception) { statusMessage = "Error: ${e.message}" }
            }
        }
        pendingBackupUri = null
    }

    fun performRestore(password: String) {
        pendingRestoreUri?.let { uri ->
            scope.launch {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream == null) { statusMessage = "Error: Could not open backup file"; return@launch }
                    val data = inputStream.use { it.readBytes() }
                    if (data.isEmpty()) { statusMessage = "Error: Backup file is empty"; return@launch }
                    val result = backupManager.restoreBackup(password, data, true)
                    result.onSuccess { stats ->
                        statusMessage = "Restored: ${stats.totalEvents} events"
                        val (allSessions, totalEvCount) = withContext(Dispatchers.IO) {
                            Pair(dao.getAllSessions(), dao.getTotalEventCount())
                        }
                        sessions = allSessions
                        totalSessions = allSessions.size
                        totalEvents = totalEvCount
                    }.onFailure { e -> statusMessage = "Restore failed: ${e.message}" }
                } catch (e: Exception) { statusMessage = "Error: ${e.message}" }
            }
        }
        pendingRestoreUri = null
    }

    // Filter sessions for All Recent view
    val filteredSessions by remember(sessions, searchQuery, selectedFilter) {
        derivedStateOf {
            val filtered = if (searchQuery.isBlank()) sessions else sessions.filter { session ->
                session.previewText?.contains(searchQuery, ignoreCase = true) == true ||
                session.appName?.contains(searchQuery, ignoreCase = true) == true ||
                session.appPackage.contains(searchQuery, ignoreCase = true)
            }
            when (selectedFilter) {
                FilterType.ALL -> filtered
                FilterType.PASSWORDS -> filtered.filter { it.hasPasswords }
                FilterType.DELETES -> filtered.filter { it.totalDeletes > 0 }
                FilterType.LINE_BREAKS -> filtered.filter { it.totalLinebreaks > 0 }
                FilterType.TYPED -> filtered.filter { it.totalTyped > 0 }
                FilterType.LONG_PARAGRAPHS -> filtered.filter { (it.totalTyped + it.totalLinebreaks) > 50 }
            }
        }
    }

    // Filter calendar sessions by search/filter
    val filteredCalendarSessions by remember(calendarSessions, searchQuery, selectedFilter) {
        derivedStateOf {
            val filtered = if (searchQuery.isBlank()) calendarSessions else calendarSessions.filter { session ->
                session.previewText?.contains(searchQuery, ignoreCase = true) == true ||
                session.appName?.contains(searchQuery, ignoreCase = true) == true ||
                session.appPackage.contains(searchQuery, ignoreCase = true)
            }
            when (selectedFilter) {
                FilterType.ALL -> filtered
                FilterType.PASSWORDS -> filtered.filter { it.hasPasswords }
                FilterType.DELETES -> filtered.filter { it.totalDeletes > 0 }
                FilterType.LINE_BREAKS -> filtered.filter { it.totalLinebreaks > 0 }
                FilterType.TYPED -> filtered.filter { it.totalTyped > 0 }
                FilterType.LONG_PARAGRAPHS -> filtered.filter { (it.totalTyped + it.totalLinebreaks) > 50 }
            }
        }
    }

    fun loadEvents(sessionId: String): List<TypingHistoryDao.TypingEvent> {
        return eventsCache[sessionId] ?: dao.getEventsBySession(sessionId).also {
            eventsCache = eventsCache + (sessionId to it)
        }
    }

    Scaffold(
        topBar = {
            if (multiSelectActive) {
                TopAppBar(
                    title = { Text(stringResource(R.string.typing_history_n_selected, selectedSessionIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedSessionIds = emptySet() }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.dialog_close))
                        }
                    },
                    actions = {
                        val allSelected = filteredSessions.isNotEmpty() &&
                            filteredSessions.all { it.sessionId in selectedSessionIds }
                        TextButton(onClick = {
                            selectedSessionIds = if (allSelected) emptySet()
                            else filteredSessions.map { it.sessionId }.toSet()
                        }) {
                            Text(
                                stringResource(
                                    if (allSelected) R.string.typing_history_deselect_all
                                    else R.string.typing_history_select_all
                                )
                            )
                        }
                        IconButton(onClick = { showBulkDeleteConfirm = true }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.typing_history_delete_session),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            } else {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_screen_typing_history)) },
                navigationIcon = {
                    IconButton(onClick = onClickBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
                actions = {
                    if (isUnlocked) {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(
                                if (showSearch) Icons.Default.Clear else Icons.Default.Search,
                                contentDescription = if (showSearch) stringResource(R.string.close_search) else stringResource(R.string.search_history)
                            )
                        }
                        IconButton(onClick = { showSettingsBar = !showSettingsBar }) {
                            Icon(
                                if (showSettingsBar) Icons.Default.Clear else Icons.Default.Settings,
                                contentDescription = if (showSettingsBar) stringResource(R.string.close_settings) else stringResource(R.string.settings)
                            )
                        }
                    }
                }
            )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isUnlocked) {
                // Section switcher: All Recent / Calendar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ViewMode.entries.forEach { mode ->
                        val selected = viewMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                                .clickable { viewMode = mode }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (mode) {
                                    ViewMode.ALL_RECENT -> stringResource(R.string.typing_history_all_recent)
                                    ViewMode.CALENDAR -> stringResource(R.string.typing_history_calendar)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Settings bar (collapsible)
                AnimatedVisibility(visible = showSettingsBar) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Recording", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        if (isEnabled) "Active" else "Paused",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled) { securityManager.enableFeature(); isEnabled = true }
                                        else { securityManager.disableFeature(); isEnabled = false }
                                    }
                                )
                            }

                            HorizontalDivider()

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(totalSessions.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                                    Text("Sessions", style = MaterialTheme.typography.bodySmall)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(totalEvents.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                                    Text("Events", style = MaterialTheme.typography.bodySmall)
                                }
                            }


                            OutlinedButton(
                                onClick = { showChangePasswordDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (securityManager.hasPassword())
                                        stringResource(R.string.typing_history_change_password)
                                    else
                                        stringResource(R.string.typing_history_set_password)
                                )
                            }

                            OutlinedButton(
                                onClick = onClickExcludedApps,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.typing_history_exclude_apps))
                            }

                            Spacer(Modifier.height(4.dp))

                            Button(
                                onClick = {
                                    val fileName = backupManager.getDefaultBackupFileName()
                                    backupLauncher.launch(fileName)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.typing_history_backup))
                            }

                            OutlinedButton(
                                onClick = {
                                    restoreLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.typing_history_restore))
                            }

                            OutlinedButton(
                                onClick = { showClearConfirm = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.typing_history_clear_all))
                            }

                            statusMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (msg.contains("Error") || msg.contains("failed"))
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // Search bar
                if (showSearch) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.typing_history_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_search))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                // ── Filter chips (shared by both modes, scrollable) ──
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(FilterType.entries) { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = {
                                Text(
                                    when (filter) {
                                        FilterType.ALL -> stringResource(R.string.typing_history_filter_all)
                                        FilterType.TYPED -> stringResource(R.string.typing_history_filter_typed)
                                        FilterType.PASSWORDS -> stringResource(R.string.typing_history_filter_passwords)
                                        FilterType.DELETES -> stringResource(R.string.typing_history_filter_deletes)
                                        FilterType.LINE_BREAKS -> stringResource(R.string.typing_history_filter_line_breaks)
                                        FilterType.LONG_PARAGRAPHS -> stringResource(R.string.typing_history_filter_long_paragraphs)
                                    }
                                )
                            }
                        )
                    }
                }	                // ── Content area ──
	                if (isLoadingSessions) {
	                    // Loading skeleton
	                    Column(
	                        modifier = Modifier
	                            .weight(1f)
	                            .padding(horizontal = 16.dp)
	                    ) {
	                        repeat(5) {
	                            Card(
	                                modifier = Modifier
	                                    .fillMaxWidth()
	                                    .padding(vertical = 4.dp),
	                                colors = CardDefaults.cardColors(
	                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
	                                )
	                            ) {
	                                Row(
	                                    modifier = Modifier
	                                        .fillMaxWidth()
	                                        .height(56.dp)
	                                        .padding(horizontal = 12.dp),
	                                    verticalAlignment = Alignment.CenterVertically
	                                ) {
	                                    Box(
	                                        modifier = Modifier
	                                            .size(20.dp)
	                                            .clip(RoundedCornerShape(4.dp))
	                                            .background(MaterialTheme.colorScheme.surfaceVariant)
	                                    )
	                                    Spacer(Modifier.width(8.dp))
	                                    Box(
	                                        modifier = Modifier
	                                            .width(60.dp)
	                                            .height(12.dp)
	                                            .clip(RoundedCornerShape(4.dp))
	                                            .background(MaterialTheme.colorScheme.surfaceVariant)
	                                    )
	                                    Spacer(Modifier.weight(1f))
	                                    Box(
	                                        modifier = Modifier
	                                            .width(100.dp)
	                                            .height(12.dp)
	                                            .clip(RoundedCornerShape(4.dp))
	                                            .background(MaterialTheme.colorScheme.surfaceVariant)
	                                    )
	                                }
	                            }
	                        }
	                    }
	                } else when (viewMode) {
	                    ViewMode.ALL_RECENT -> {
	                        Column(
	                            modifier = Modifier
	                                .weight(1f)
	                                .padding(horizontal = 16.dp)
	                        ) {
	                            if (filteredSessions.isEmpty()) {
	                                EmptyHistoryView()
	                            } else {
	                                LazyColumn(
	                                    contentPadding = PaddingValues(bottom = 16.dp)
	                                ) {
	                                    items(items = filteredSessions, key = { it.sessionId }) { session ->
	                                        SwipeableSessionCard(
	                                            session = session,
	                                            events = loadEvents(session.sessionId),
	                                            maskPassword = maskPassword,
	                                            highlightText = if (searchQuery.isBlank()) null else searchQuery,
	                                            selectionMode = multiSelectActive,
	                                            selected = session.sessionId in selectedSessionIds,
	                                            onToggleSelected = {
	                                                selectedSessionIds =
	                                                    if (session.sessionId in selectedSessionIds)
	                                                        selectedSessionIds - session.sessionId
	                                                    else
	                                                        selectedSessionIds + session.sessionId
	                                            },
	                                            onLongPress = {
	                                                selectedSessionIds = selectedSessionIds + session.sessionId
	                                            },
	                                            onRequestDelete = { deleteTarget = session.sessionId }
	                                        )
	                                    }
	                                }
	                            }
	                        }
	                    }
	                    ViewMode.CALENDAR -> {
	                        Column(
	                            modifier = Modifier
	                                .weight(1f)
	                                .padding(horizontal = 16.dp)
	                        ) {
	                            // ── Compact date strip: ← 9 10 11 [12] 13 → ──
	                            Row(
	                                modifier = Modifier
	                                    .fillMaxWidth()
	                                    .padding(vertical = 4.dp),
	                                horizontalArrangement = Arrangement.SpaceBetween,
	                                verticalAlignment = Alignment.CenterVertically
	                            ) {
	                                IconButton(onClick = {
	                                    centeredDateMillis -= 86400000
	                                    selectedDateMillis = centeredDateMillis
	                                }) {
	                                    Text("←", style = MaterialTheme.typography.titleMedium)
	                                }

	                                for (offset in -2..2) {
	                                    val dayMillis = centeredDateMillis + offset * 86400000
	                                    val isSelected = selectedDateMillis == dayMillis
	                                    val isToday = dayMillis == startOfLocalDay()

	                                    Column(
	                                        modifier = Modifier
	                                            .clip(RoundedCornerShape(8.dp))
	                                            .background(
	                                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
	                                                else MaterialTheme.colorScheme.surface
	                                            )
	                                            .clickable {
	                                                selectedDateMillis = dayMillis
	                                                centeredDateMillis = dayMillis
	                                            }
	                                            .padding(horizontal = 12.dp, vertical = 6.dp),
	                                        horizontalAlignment = Alignment.CenterHorizontally
	                                    ) {
	                                        Text(
	                                            text = dayNameFormat.format(Date(dayMillis)),
	                                            style = MaterialTheme.typography.labelSmall,
	                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
	                                                    else MaterialTheme.colorScheme.onSurfaceVariant
	                                        )
	                                        Text(
	                                            text = dayFormat.format(Date(dayMillis)),
	                                            style = MaterialTheme.typography.titleMedium,
	                                            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
	                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
	                                                    else MaterialTheme.colorScheme.onSurface
	                                        )
                                        // Dot indicator: primary if has history, subtle if today
                                        val hasHistory = datesWithHistory.contains(startOfLocalDay(dayMillis))
                                        if (hasHistory || isToday) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(top = 2.dp)
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (hasHistory) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                                    )
                                            )
                                        }
	                                    }
	                                }

	                                IconButton(onClick = {
	                                    centeredDateMillis += 86400000
	                                    selectedDateMillis = centeredDateMillis
	                                }) {
	                                    Text("→", style = MaterialTheme.typography.titleMedium)
	                                }
	                            }

	                            // ── Expand / Collapse full calendar ──
	                            OutlinedButton(
	                                onClick = { showFullCalendar = !showFullCalendar },
	                                modifier = Modifier.fillMaxWidth()
	                            ) {
	                                Text(
	                                    text = if (showFullCalendar)
	                                        "📅 ${monthFormat.format(Date(centeredDateMillis))}  ▲ Collapse"
	                                    else
	                                        "📅 ${monthFormat.format(Date(centeredDateMillis))}  ▼ Expand full calendar",
	                                    style = MaterialTheme.typography.bodyMedium
	                                )
	                            }

	                            // ── Inline full calendar (Material 3 DatePicker) ──
	                            if (showFullCalendar) {
	                                Spacer(modifier = Modifier.height(8.dp))
	                                val datePickerState = rememberDatePickerState(
	                                    initialSelectedDateMillis = selectedDateMillis ?: System.currentTimeMillis()
	                                )
	                                LaunchedEffect(datePickerState.selectedDateMillis) {
	                                    datePickerState.selectedDateMillis?.let { millis ->
	                                        selectedDateMillis = millis
	                                        centeredDateMillis = millis
	                                    }
	                                }
	                                DatePicker(
	                                    state = datePickerState,
	                                    modifier = Modifier.fillMaxWidth()
	                                )
	                            }

                            // ── Sessions for selected date (2-hour accordion) ──
                            if (filteredCalendarSessions.isEmpty()) {
                                EmptyHistoryView()
                            } else {
                                Text(
                                    text = dateFormat.format(Date(selectedDateMillis ?: System.currentTimeMillis())),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                // Flatten all events for the selected day, bucket into 2-hour windows
                                val dayEvents = remember(filteredCalendarSessions) {
                                    filteredCalendarSessions
                                        .flatMap { loadEvents(it.sessionId) }
                                        .sortedBy { it.timestamp }
                                }
                                val buckets = remember(dayEvents) {
                                    bucketEventsByTwoHours(dayEvents)
                                }

                                TimeBucketAccordion(
                                    buckets = buckets,
                                    maskPassword = maskPassword,
                                    modifier = Modifier.weight(1f)
                                )
                            }
	                        }
	                    }
	                }
            }
        }
    }

    // Password dialog
    if (showPasswordDialog) {
        TypingHistoryPasswordDialog(
            onDismissRequest = { showPasswordDialog = false; onClickBack() },
            onPasswordVerified = { showPasswordDialog = false; isUnlocked = true },
            mode = passwordDialogMode
        )
    }

    // Change password dialog
    if (showChangePasswordDialog) {
        TypingHistoryPasswordDialog(
            onDismissRequest = { showChangePasswordDialog = false },
            onPasswordVerified = {
                showChangePasswordDialog = false
                statusMessage = if (securityManager.hasPassword()) "Password changed" else "Password set"
            },
            mode = if (securityManager.hasPassword()) PasswordDialogMode.CHANGE else PasswordDialogMode.SETUP
        )
    }

    // Delete session confirmation (triggered by a swipe)
    deleteTarget?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.typing_history_delete_session)) },
            text = { Text(stringResource(R.string.typing_history_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { deleteSession(sessionId) }) {
                    Text(stringResource(R.string.dialog_close), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.dialog_close))
                }
            }
        )
    }

    // Bulk delete confirmation (triggered from the multi-select selection bar)
    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text(stringResource(R.string.typing_history_delete_session)) },
            text = {
                Text(
                    stringResource(
                        R.string.typing_history_delete_selected_confirm,
                        selectedSessionIds.size
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteSessions(selectedSessionIds) }) {
                    Text(stringResource(R.string.dialog_close), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) {
                    Text(stringResource(R.string.dialog_close))
                }
            }
        )
    }

    // Backup password dialog
    if (showBackupPasswordDialog) {
        BackupPasswordDialog(
            onDismissRequest = { showBackupPasswordDialog = false; pendingBackupUri = null },
            onPasswordEntered = { password -> showBackupPasswordDialog = false; if (password.isNotEmpty()) performBackup(password) },
            title = "Backup",
            description = "Set a password to encrypt your backup."
        )
    }

    // Restore password dialog
    if (showRestorePasswordDialog) {
        BackupPasswordDialog(
            onDismissRequest = { showRestorePasswordDialog = false; pendingRestoreUri = null },
            onPasswordEntered = { password -> showRestorePasswordDialog = false; if (password.isNotEmpty()) performRestore(password) },
            title = "Restore",
            description = "Enter the backup password."
        )
    }

    // Clear all confirmation
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear All History") },
            text = { Text("This will permanently delete all typing history. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    dao.deleteAllHistory()
                    sessions = emptyList(); totalSessions = 0; totalEvents = 0; eventsCache = emptyMap()
                    calendarSessions = emptyList(); statusMessage = "All history cleared"; showClearConfirm = false
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Empty history placeholder
 */
@Composable
private fun EmptyHistoryView() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.History,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.typing_history_no_sessions),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.typing_history_no_sessions_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Filter types for history viewer
 */
enum class FilterType {
    ALL, TYPED, PASSWORDS, DELETES, LINE_BREAKS, LONG_PARAGRAPHS
}

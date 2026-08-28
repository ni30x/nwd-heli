// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.TypingHistoryBackupManager
import helium314.keyboard.latin.database.TypingHistoryDao
import helium314.keyboard.latin.TypingHistorySecurityManager
import helium314.keyboard.settings.dialogs.TypingHistoryPasswordDialog
import helium314.keyboard.settings.dialogs.PasswordDialogMode
import helium314.keyboard.settings.dialogs.BackupPasswordDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manage history screen — backup and delete functionality.
 * Accessed from the viewer's menu (⋮).
 * SEPARATE PAGE to prevent accidental deletion while browsing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypingHistoryManageScreen(
    onClickBack: () -> Unit
) {
    val context = LocalContext.current
    val dao = TypingHistoryDao.getInstance(context)
    val backupManager = TypingHistoryBackupManager(context)
    val securityManager = TypingHistorySecurityManager.getInstance(context)
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("heliboard_preferences", 0)
    
    // State
    var totalSessions by remember { mutableStateOf(0) }
    var totalEvents by remember { mutableStateOf(0) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showBackupPasswordDialog by remember { mutableStateOf(false) }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var passwordDialogAction by remember { mutableStateOf<PasswordAction?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var pendingBackupUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    
    // Load stats
    LaunchedEffect(Unit) {
        totalSessions = dao.getAllSessions().size
        totalEvents = dao.getTotalEventCount()
    }
    
    // Backup launcher — file picker
    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            pendingBackupUri = it
            showBackupPasswordDialog = true
        }
    }

    // Restore launcher — file picker
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            pendingRestoreUri = it
            showRestorePasswordDialog = true
        }
    }
    
    // Perform backup after password is entered
    fun performBackup(password: String) {
        pendingBackupUri?.let { uri ->
            scope.launch {
                try {
                    val outputStream = context.contentResolver.openOutputStream(uri)
                    if (outputStream == null) {
                        backupMessage = "Error: Could not create backup file"
                        return@launch
                    }
                    val stats = backupManager.createBackup(password, outputStream)
                    stats.onSuccess { result ->
                        backupMessage = "Backup created: ${result.totalEvents} events, ${result.totalSessions} sessions"
                        totalSessions = dao.getAllSessions().size
                        totalEvents = dao.getTotalEventCount()
                    }.onFailure { e ->
                        backupMessage = "Backup failed: ${e.message}"
                    }
                } catch (e: Exception) {
                    backupMessage = "Error: ${e.message}"
                }
            }
        }
        pendingBackupUri = null
    }
    
    // Perform restore after password is entered
    fun performRestore(password: String) {
        pendingRestoreUri?.let { uri ->
            scope.launch {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream == null) {
                        backupMessage = "Error: Could not open backup file"
                        return@launch
                    }
                    val data = inputStream.use { it.readBytes() }
                    if (data.isEmpty()) {
                        backupMessage = "Error: Backup file is empty"
                        return@launch
                    }
                    val result = backupManager.restoreBackup(password, data, true)
                    result.onSuccess { stats ->
                        backupMessage = "Restored: ${stats.totalEvents} events from ${stats.totalSessions} sessions"
                        totalSessions = dao.getAllSessions().size
                        totalEvents = dao.getTotalEventCount()
                    }.onFailure { e ->
                        backupMessage = "Restore failed: ${e.message}"
                    }
                } catch (e: Exception) {
                    backupMessage = "Error: ${e.message}"
                }
            }
        }
        pendingRestoreUri = null
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.typing_history_manage)) },
                navigationIcon = {
                    IconButton(onClick = onClickBack) {
                        Text("←")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ─── Statistics ───
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.typing_history_statistics),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = totalSessions.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.typing_history_sessions),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = totalEvents.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.typing_history_events),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            
            // ─── Backup Section ───
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.typing_history_backup),
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Button(
                        onClick = {
                            val fileName = backupManager.getDefaultBackupFileName()
                            backupLauncher.launch(fileName)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📦 " + stringResource(R.string.typing_history_backup))
                    }
                    
                    OutlinedButton(
                        onClick = {
                            restoreLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📂 " + stringResource(R.string.typing_history_restore))
                    }
                    
                    // Backup message
                    backupMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.contains("Error") || message.contains("failed"))
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            // ─── Delete Section ───
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "⚠️ " + stringResource(R.string.typing_history_clear_all),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    
                    // Clear all history
                    OutlinedButton(
                        onClick = {
                            if (securityManager.hasPassword()) {
                                passwordDialogAction = PasswordAction.DELETE_ALL
                                showPasswordDialog = true
                            } else {
                                // Confirm without password
                                passwordDialogAction = PasswordAction.DELETE_ALL
                                showPasswordDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("🗑 " + stringResource(R.string.typing_history_clear_all))
                    }
                    
                    Text(
                        text = stringResource(R.string.typing_history_manage_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    
    // Password dialog for delete confirmation
    if (showPasswordDialog) {
        TypingHistoryPasswordDialog(
            onDismissRequest = {
                showPasswordDialog = false
                passwordDialogAction = null
            },
            onPasswordVerified = {
                // Perform the action
                when (passwordDialogAction) {
                    PasswordAction.DELETE_ALL -> {
                        dao.deleteAllHistory()
                        totalSessions = 0
                        totalEvents = 0
                        backupMessage = "All history cleared"
                    }
                    else -> {}
                }
                showPasswordDialog = false
                passwordDialogAction = null
            },
            mode = PasswordDialogMode.VERIFY
        )
    }
    
    // Backup password dialog — set password for encrypted backup
    if (showBackupPasswordDialog) {
        BackupPasswordDialog(
            onDismissRequest = {
                showBackupPasswordDialog = false
                pendingBackupUri = null
            },
            onPasswordEntered = { password ->
                showBackupPasswordDialog = false
                if (password.isNotEmpty()) {
                    performBackup(password)
                }
            },
            title = stringResource(R.string.typing_history_backup),
            description = "Set a password to encrypt your backup file. Remember this password — you'll need it to restore."
        )
    }
    
    // Restore password dialog — enter password to decrypt backup
    if (showRestorePasswordDialog) {
        BackupPasswordDialog(
            onDismissRequest = {
                showRestorePasswordDialog = false
                pendingRestoreUri = null
            },
            onPasswordEntered = { password ->
                showRestorePasswordDialog = false
                if (password.isNotEmpty()) {
                    performRestore(password)
                }
            },
            title = stringResource(R.string.typing_history_restore),
            description = "Enter the password that was used to encrypt this backup."
        )
    }
}

/**
 * Password-protected actions
 */
private enum class PasswordAction {
    DELETE_ALL,
    DELETE_BY_DATE,
    DELETE_BY_APP
}

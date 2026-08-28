// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.TypingHistorySecurityManager
import helium314.keyboard.latin.database.TypingHistoryDao
import helium314.keyboard.settings.dialogs.TypingHistoryPasswordDialog
import helium314.keyboard.settings.dialogs.PasswordDialogMode

/**
 * Settings screen for typing history feature
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypingHistorySettingsScreen(
    onClickBack: () -> Unit,
    onViewHistory: () -> Unit
) {
    val context = LocalContext.current
    val securityManager = TypingHistorySecurityManager.getInstance(context)
    val dao = TypingHistoryDao.getInstance(context)
    
    var isEnabled by remember { mutableStateOf(securityManager.isFeatureEnabled()) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordDialogMode by remember { mutableStateOf(PasswordDialogMode.SETUP) }
    var showPasswordVerifyDialog by remember { mutableStateOf(false) }
    
    // Get stats
    val totalSessions = remember { mutableStateOf(0) }
    val totalEvents = remember { mutableStateOf(0) }
    
    LaunchedEffect(isEnabled) {
        if (isEnabled) {
            totalSessions.value = dao.getAllSessions().size
            totalEvents.value = dao.getTotalEventCount()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_screen_typing_history)) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Enable/Disable switch
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.typing_history_enable),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.typing_history_enable_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                // Enable feature
                                securityManager.enableFeature()
                                isEnabled = true
                            } else {
                                // Disable feature (requires password if set)
                                if (securityManager.hasPassword()) {
                                    passwordDialogMode = PasswordDialogMode.VERIFY
                                    showPasswordDialog = true
                                } else {
                                    securityManager.disableFeature()
                                    isEnabled = false
                                }
                            }
                        }
                    )
                }
            }
            
            if (isEnabled) {
                // Password protection
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.typing_history_password_protection),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (securityManager.hasPassword()) 
                                    stringResource(R.string.typing_history_password_set)
                                else 
                                    stringResource(R.string.typing_history_password_not_set),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = {
                                passwordDialogMode = if (securityManager.hasPassword()) 
                                    PasswordDialogMode.CHANGE 
                                else 
                                    PasswordDialogMode.SETUP
                                showPasswordDialog = true
                            }
                        ) {
                            Text(
                                if (securityManager.hasPassword()) 
                                    stringResource(R.string.typing_history_change_password)
                                else 
                                    stringResource(R.string.typing_history_set_password)
                            )
                        }
                    }
                }
                
                // Statistics
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
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
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Text(
                                    text = totalSessions.value.toString(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.typing_history_sessions),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Text(
                                    text = totalEvents.value.toString(),
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
                
                // View History button
                Button(
                    onClick = {
                        if (securityManager.hasPassword()) {
                            showPasswordVerifyDialog = true
                        } else {
                            onViewHistory()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(stringResource(R.string.typing_history_view_history))
                }
            }
        }
    }
    
    // Password setup/change dialog
    if (showPasswordDialog) {
        TypingHistoryPasswordDialog(
            onDismissRequest = { showPasswordDialog = false },
            onPasswordVerified = {
                showPasswordDialog = false
                // Refresh state
                isEnabled = securityManager.isFeatureEnabled()
            },
            mode = passwordDialogMode
        )
    }
    
    // Password verification dialog for viewing history
    if (showPasswordVerifyDialog) {
        TypingHistoryPasswordDialog(
            onDismissRequest = { showPasswordVerifyDialog = false },
            onPasswordVerified = {
                showPasswordVerifyDialog = false
                onViewHistory()
            },
            mode = PasswordDialogMode.VERIFY
        )
    }
}

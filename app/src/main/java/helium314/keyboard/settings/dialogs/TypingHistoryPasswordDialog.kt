// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.TypingHistorySecurityManager

/**
 * Dialog for setting up or verifying typing history password
 */
@Composable
fun TypingHistoryPasswordDialog(
    onDismissRequest: () -> Unit,
    onPasswordVerified: () -> Unit,
    mode: PasswordDialogMode = PasswordDialogMode.VERIFY
) {
    val context = LocalContext.current
    val securityManager = TypingHistorySecurityManager.getInstance(context)
    
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val title = when (mode) {
        PasswordDialogMode.SETUP -> stringResource(R.string.typing_history_set_password)
        PasswordDialogMode.VERIFY -> stringResource(R.string.typing_history_enter_password)
        PasswordDialogMode.CHANGE -> stringResource(R.string.typing_history_change_password)
    }
    
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Password field
                OutlinedTextField(
                    value = password,
                    onValueChange = { 
                        password = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.typing_history_password)) },
                    visualTransformation = if (showPassword) VisualTransformation.None 
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "👁" else "👁‍🗨")
                        }
                    },
                    isError = errorMessage != null
                )
                
                // Confirm password field (only for setup/change mode)
                if (mode != PasswordDialogMode.VERIFY) {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { 
                            confirmPassword = it
                            errorMessage = null
                        },
                        label = { Text(stringResource(R.string.typing_history_confirm_password)) },
                        visualTransformation = if (showPassword) VisualTransformation.None 
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = errorMessage != null
                    )
                }
                
                // Error message
                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (mode) {
                        PasswordDialogMode.SETUP, PasswordDialogMode.CHANGE -> {
                            if (password.length < 4) {
                                errorMessage = context.getString(R.string.typing_history_password_too_short)
                                return@Button
                            }
                            if (password != confirmPassword) {
                                errorMessage = context.getString(R.string.typing_history_passwords_dont_match)
                                return@Button
                            }
                            if (securityManager.setPassword(password)) {
                                onPasswordVerified()
                            } else {
                                errorMessage = context.getString(R.string.typing_history_password_error)
                            }
                        }
                        PasswordDialogMode.VERIFY -> {
                            if (password.isEmpty()) {
                                errorMessage = context.getString(R.string.typing_history_password_too_short)
                                return@Button
                            }
                            if (securityManager.verifyPassword(password)) {
                                onPasswordVerified()
                            } else {
                                errorMessage = context.getString(R.string.typing_history_wrong_password)
                            }
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.dialog_close))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.dialog_close))
            }
        }
    )
}

/**
 * Password dialog modes
 */
enum class PasswordDialogMode {
    SETUP,    // First time setup
    VERIFY,   // Verify existing password
    CHANGE    // Change existing password
}

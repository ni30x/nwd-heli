// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.TypingHistoryRecorder
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.screens.components.AppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledAppEntry(
    val packageName: String,
    val label: String
)

/**
 * Lets the user pick which apps are excluded from typing history recording.
 *
 * This preference (PREF_TYPING_HISTORY_EXCLUDE_APPS / readTypingHistoryExcludeApps)
 * already existed in Settings.java, and TypingHistoryRecorder already had the
 * plumbing to skip recording for a package on this list — but there was no UI
 * anywhere to actually add an app to it. This screen is that missing UI.
 *
 * Selected apps are stored as a comma-separated list of package names, using the
 * same separator TypingHistoryRecorder.isAppExcluded() already splits on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypingHistoryExcludedAppsScreen(
    onClickBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("heliboard_preferences", 0) }

    var excludedPackages by remember {
        mutableStateOf(
            Settings.readTypingHistoryExcludeApps(prefs)
                .split(TypingHistoryRecorder.EXCLUDED_APPS_SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        )
    }

    fun persist(newSet: Set<String>) {
        excludedPackages = newSet
        Settings.writeTypingHistoryExcludeApps(
            prefs,
            newSet.joinToString(TypingHistoryRecorder.EXCLUDED_APPS_SEPARATOR)
        )
    }

    var allApps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    // Loading the full installed-app list touches PackageManager, which can be
    // slow with many apps installed — keep it off the main thread.
    LaunchedEffect(Unit) {
        allApps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val ownPackage = context.packageName
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != ownPackage }
                // Skip pure system components with no launchable UI where possible;
                // still allow anything else through so the user isn't blocked from
                // excluding something unusual.
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { InstalledAppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
                .toList()
        }
        isLoading = false
    }

    val filtered = remember(allApps, query) {
        if (query.isBlank()) allApps
        else allApps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    // Selected apps first (so the current exclusion list is visible at a glance),
    // then the rest alphabetically.
    val sorted = remember(filtered, excludedPackages) {
        filtered.sortedWith(
            compareByDescending<InstalledAppEntry> { it.packageName in excludedPackages }
                .thenBy { it.label.lowercase() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.typing_history_exclude_apps)) },
                navigationIcon = {
                    IconButton(onClick = onClickBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Text(
                text = stringResource(R.string.typing_history_exclude_apps_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.typing_history_exclude_apps_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            if (excludedPackages.isEmpty() && query.isBlank()) {
                Text(
                    text = stringResource(R.string.typing_history_exclude_apps_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(items = sorted, key = { it.packageName }) { app ->
                        val isExcluded = app.packageName in excludedPackages
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    persist(
                                        if (isExcluded) excludedPackages - app.packageName
                                        else excludedPackages + app.packageName
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(packageName = app.packageName, size = 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = isExcluded,
                                onCheckedChange = {
                                    persist(
                                        if (isExcluded) excludedPackages - app.packageName
                                        else excludedPackages + app.packageName
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

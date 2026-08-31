// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens.components

import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.TypingHistoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ─── Time formatting helper ───
private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

// ─── App icon cache (shared across all SessionCards) ───
private val appIconCache = LruCache<String, android.graphics.Bitmap>(50)

/**
 * Reusable composable that loads and displays a real per-app icon.
 * Caches the resolved Bitmap per packageName in an LruCache so repeated
 * SessionCards for the same app do not re-query PackageManager.
 * Loading happens off the main thread; a lightweight placeholder is shown until ready.
 */
@Composable
fun AppIcon(packageName: String, size: Dp = 20.dp) {
    val context = LocalContext.current
    var bitmap by remember(packageName) {
        mutableStateOf(appIconCache.get(packageName))
    }

    // Load icon off the main thread; falls back to Material Icons.Outlined.Apps on failure
    LaunchedEffect(packageName) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.IO) {
                try {
                    val icon = context.packageManager.getApplicationIcon(packageName)
                    val bmp = android.graphics.Bitmap.createBitmap(
                        icon.intrinsicWidth.coerceAtLeast(1),
                        icon.intrinsicHeight.coerceAtLeast(1),
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bmp)
                    icon.setBounds(0, 0, canvas.width, canvas.height)
                    icon.draw(canvas)
                    appIconCache.put(packageName, bmp)
                    bmp
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = packageName,
            modifier = Modifier.size(size)
        )
    } else {
        // Placeholder while loading or on failure
        Text(
            text = "⊞",
            fontSize = (size.value * 0.8).sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size)
        )
    }
}

/**
 * Row for normal typed text — plain text, no icon
 */
@Composable
fun TypedEventRow(event: TypingHistoryDao.TypingEvent, highlightText: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Timestamp
        Text(
            text = formatTime(event.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Typed text (with highlight support)
        val text = event.textContent ?: ""
        if (highlightText != null && text.contains(highlightText, ignoreCase = true)) {
            // Use AnnotatedString for highlight
            val annotatedText = buildAnnotatedString {
                val lower = text.lowercase()
                val query = highlightText.lowercase()
                var lastIndex = 0
                var index = lower.indexOf(query, lastIndex)
                while (index != -1) {
                    append(text.substring(lastIndex, index))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Color(0xFFFFEB3B).copy(alpha = 0.4f))) {
                        append(text.substring(index, index + highlightText.length))
                    }
                    lastIndex = index + highlightText.length
                    index = lower.indexOf(query, lastIndex)
                }
                append(text.substring(lastIndex))
            }
            Text(
                text = annotatedText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Row for password events — masked text + lock icon + red dot + reveal button
 */
@Composable
fun PasswordEventRow(
    event: TypingHistoryDao.TypingEvent,
    maskPassword: Boolean = true
) {
    var revealed by remember { mutableStateOf(!maskPassword) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Timestamp
        Text(
            text = formatTime(event.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Password text (masked with dots or revealed)
        val text = event.textContent ?: ""
        Text(
            text = if (revealed) text else "•".repeat(text.length.coerceAtLeast(1)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        
        // Lock icon
        Icon(
            Icons.Outlined.Lock,
            contentDescription = stringResource(R.string.typing_history_reveal_password),
            modifier = Modifier
                .size(16.dp)
                .padding(horizontal = 4.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        // Red dot indicator
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(MaterialTheme.colorScheme.error, CircleShape)
        )
        
        // Reveal/hide toggle
        IconButton(
            onClick = { revealed = !revealed },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (revealed) stringResource(R.string.typing_history_hide_password) else stringResource(R.string.typing_history_reveal_password),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Row for delete/backspace events — deleted text with single delete sign on right side
 */
@Composable
fun DeleteEventRow(event: TypingHistoryDao.TypingEvent) {
    val gray = MaterialTheme.colorScheme.onSurfaceVariant
    val deleteColor = MaterialTheme.colorScheme.error

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Timestamp
        Text(
            text = formatTime(event.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = gray,
            modifier = Modifier.width(60.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Deleted text (strikethrough)
        val deletedText = event.deletedText
        val charCount = event.charCount

        Text(
            text = when {
                !deletedText.isNullOrEmpty() ->
                    stringResource(R.string.typing_history_deleted_text, deletedText)
                charCount > 0 ->
                    stringResource(R.string.typing_history_deleted_chars, charCount)
                else -> stringResource(R.string.typing_history_event_delete)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = gray,
            textDecoration = TextDecoration.LineThrough,
            modifier = Modifier.weight(1f)
        )

        // Single delete icon on the RIGHT side — clearly marks deleted content
        Icon(
            Icons.Default.Backspace,
            contentDescription = null,
            modifier = Modifier
                .size(16.dp)
                .padding(start = 6.dp),
            tint = deleteColor
        )
    }
}

/**
 * Row for line break events — horizontal divider with ↵ icon
 */
@Composable
fun LineBreakEventRow(event: TypingHistoryDao.TypingEvent) {
    val gray = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Timestamp
        Text(
            text = formatTime(event.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = gray,
            modifier = Modifier.width(60.dp)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Divider line
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = gray,
            thickness = 1.dp
        )
        
        // Enter icon
        Text(
            text = "↵",
            fontSize = 14.sp,
            color = gray,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = gray,
            thickness = 1.dp
        )
    }
}

/**
 * Session card — one-line compact, expandable to show full text + delete indicators
 *
 * Compact:  📱 WhatsApp · 19:52 hloo how are you
 * Expanded: full paragraph text block, then ⌫ delete indicators below
 */
@Composable
fun SessionCard(
    session: TypingHistoryDao.TypingSession,
    events: List<TypingHistoryDao.TypingEvent>,
    maskPassword: Boolean = true,
    highlightText: String? = null,
    onDeleteSession: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    // Build full paragraph text from typed + password events (joined, not split)
    val fullParagraphText = remember(events) {
        events
            .filter {
                it.eventType == TypingHistoryDao.EventType.TYPED ||
                it.eventType == TypingHistoryDao.EventType.PASSWORD
            }
            .mapNotNull { it.textContent }
            .joinToString("")
    }

    // Collect delete events for expanded view
    val deleteEvents = remember(events) {
        events.filter { it.eventType == TypingHistoryDao.EventType.DELETE }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp)
        ) {
            // ─── One-line header: 📱 App · HH:mm text ───
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // App icon
                AppIcon(packageName = session.appPackage)

                Spacer(modifier = Modifier.width(6.dp))

                // App name
                Text(
                    text = session.appPackage.let { pkg ->
                        session.appName ?: pkg.substringAfterLast('.')
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 80.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(4.dp))

                // Time
                Text(
                    text = formatTime(session.startTime),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Preview text (the actual typed content, one line)
                Text(
                    text = fullParagraphText.take(60) +
                        if (fullParagraphText.length > 60) "…" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Password dot
                if (session.hasPasswords) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                    )
                }

                // Delete count indicator
                if (session.totalDeletes > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "⌫${session.totalDeletes}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Expand chevron (animated)
                Spacer(modifier = Modifier.width(4.dp))
                val chevronRotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(durationMillis = 220)
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ─── Expanded: full text + delete indicators (animated) ───
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(150))
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Full paragraph text block
                if (fullParagraphText.isNotBlank()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        // Show text with line breaks preserved
                        val displayText = fullParagraphText.replace("\n", "↵\n")
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // Delete indicators (only if there are deletes)
                if (deleteEvents.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    deleteEvents.forEach { event ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Backspace,
                                contentDescription = stringResource(R.string.typing_history_event_delete),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when {
                                    !event.deletedText.isNullOrEmpty() ->
                                        "deleted \"${event.deletedText}\""
                                    event.charCount > 0 ->
                                        "deleted ${event.charCount} chars"
                                    else -> "deleted"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Delete session button
                if (onDeleteSession != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDeleteSession) {
                            Text(
                                text = stringResource(R.string.typing_history_delete_session),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Format session time (e.g., "2m ago" or "14:32")
 */
private fun formatSessionTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "< 1m ago"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> {
            val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  2-Hour Time-Bucket Accordion (Calendar tab only)
// ═══════════════════════════════════════════════════════════════════

private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

/** A 2-hour time window with its events. */
data class TimeBucket(
    val startHour: Int,
    val endHour: Int,
    val events: List<TypingHistoryDao.TypingEvent>
) {
    val label: String get() {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val startCal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, startHour); set(Calendar.MINUTE, 0) }
        val endCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, endHour)
            set(Calendar.MINUTE, 0)
            if (endHour == 0) add(Calendar.DAY_OF_MONTH, 1) // midnight wrap
        }
        return "${fmt.format(startCal.time)} \u2013 ${fmt.format(endCal.time)}"
    }
}

/** Bucket a flat event list into 12 possible 2-hour local-time windows. */
fun bucketEventsByTwoHours(events: List<TypingHistoryDao.TypingEvent>): List<TimeBucket> {
    val buckets = Array(12) { hour ->
        TimeBucket(startHour = hour * 2, endHour = (hour * 2 + 2) % 24, events = emptyList())
    }
    val grouped = events.groupBy { ev ->
        val cal = Calendar.getInstance().apply { timeInMillis = ev.timestamp }
        cal.get(Calendar.HOUR_OF_DAY) / 2
    }
    return grouped.map { (idx, evts) -> buckets[idx].copy(events = evts) }
        .filter { it.events.isNotEmpty() }
        .sortedBy { it.startHour }
}

/**
 * Full accordion for a list of 2-hour time buckets.
 * Only one bucket may be expanded at a time.
 */
@Composable
fun TimeBucketAccordion(
    buckets: List<TimeBucket>,
    maskPassword: Boolean = true,
    modifier: Modifier = Modifier
) {
    var expandedKey by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        modifier = modifier.animateContentSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(
            items = buckets,
            key = { it.startHour }
        ) { bucket ->
            val isExpanded = expandedKey == bucket.startHour
            TimeBucketItem(
                bucket = bucket,
                isExpanded = isExpanded,
                onToggle = {
                    expandedKey = if (isExpanded) null else bucket.startHour
                },
                maskPassword = maskPassword
            )
        }
    }
}

/**
 * Single accordion item: header with time range + count + chevron, expandable event list.
 */
@Composable
private fun TimeBucketItem(
    bucket: TimeBucket,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    maskPassword: Boolean
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220)
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            // ── Header row ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Time range label
                Text(
                    text = bucket.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                // Event count chip
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp)
                ) {
                    Text(
                        text = "${bucket.events.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                // Chevron
                Text(
                    text = "▼",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .rotate(chevronRotation)
                )
            }

            // ── Expanded content ──
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(150))
            ) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    bucket.events.forEach { event ->
                        when (event.eventType) {
                            TypingHistoryDao.EventType.TYPED ->
                                TypedEventRow(event = event)
                            TypingHistoryDao.EventType.PASSWORD ->
                                PasswordEventRow(event = event, maskPassword = maskPassword)
                            TypingHistoryDao.EventType.DELETE ->
                                DeleteEventRow(event = event)
                            TypingHistoryDao.EventType.LINE_BREAK ->
                                LineBreakEventRow(event = event)
                        }
                    }
                }
            }
        }
    }
}

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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.DeleteOutline
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
// SINGLE source of truth for how a timestamp is displayed anywhere in the typing
// history UI (session headers, event rows, AND calendar time-bucket labels).
// Previously the calendar tab's bucket labels used a separate "h:mm a" (12-hour,
// AM/PM) formatter while every event row - including the ones inside that same
// bucket - used "HH:mm" (24-hour). That mismatch, combined with the calendar tab
// rendering one row per raw event instead of one reconstructed line per session
// (see TimeBucketItem below), was reported as the calendar view showing an
// inconsistent, "one row per letter" time format compared to the All Recent tab.
private val historyTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(timestamp: Long): String {
    return historyTimeFormat.format(Date(timestamp))
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

/** Color used for deleted characters shown inline in the reconstructed replay. */
private val DeletedTextColor = Color(0xFFD32F2F)

/**
 * Reconstructs a session's full typing stream in true chronological order (sorted by
 * timestamp, then event id as a tiebreaker for same-millisecond events) so deleted
 * characters are shown INLINE, in red with a strikethrough, at the exact point they
 * were removed — instead of the old behaviour of showing the final surviving text
 * followed by a separate "deleted 3 chars" / "deleted N chars" list underneath it,
 * which made it impossible to tell what was actually typed versus removed and where.
 *
 * No extra characters, spaces, or line breaks are inserted beyond what the user
 * actually typed — segments are appended back-to-back exactly as recorded. A ↵ is
 * only added for recorded LINE_BREAK events (i.e. actual Enter presses).
 *
 * If an older DELETE event has no captured deletedText (data recorded before the
 * exact-text capture fix), it falls back to a compact inline "⌫N" marker rather
 * than a separate block, so old and new history look consistent.
 */
fun buildSessionReplay(
    events: List<TypingHistoryDao.TypingEvent>,
    maskPassword: Boolean = true
): AnnotatedString = buildAnnotatedString {
    val ordered = events.sortedWith(compareBy({ it.timestamp }, { it.id }))
    for (event in ordered) {
        when (event.eventType) {
            TypingHistoryDao.EventType.TYPED -> {
                append(event.textContent ?: "")
            }
            TypingHistoryDao.EventType.PASSWORD -> {
                val text = event.textContent ?: ""
                append(if (maskPassword) "•".repeat(text.length) else text)
            }
            TypingHistoryDao.EventType.DELETE -> {
                val deleted = event.deletedText
                withStyle(
                    SpanStyle(color = DeletedTextColor, textDecoration = TextDecoration.LineThrough)
                ) {
                    if (!deleted.isNullOrEmpty()) {
                        append(deleted)
                    } else if (event.charCount > 0) {
                        append("⌫${event.charCount}")
                    }
                }
            }
            TypingHistoryDao.EventType.LINE_BREAK -> {
                append("↵\n")
            }
        }
    }
}

/**
 * Renders the chronological replay of a session: typed text in normal color,
 * deleted text inline in red strikethrough, in perfect sequence and position.
 */
@Composable
fun SessionReplayText(
    events: List<TypingHistoryDao.TypingEvent>,
    maskPassword: Boolean = true,
    modifier: Modifier = Modifier
) {
    val replay = remember(events, maskPassword) { buildSessionReplay(events, maskPassword) }
    if (replay.text.isNotEmpty()) {
        Text(
            text = replay,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
        )
    }
}

/**
 * Session card — one-line compact, expandable to show full text + delete indicators
 *
 * Compact:  📱 WhatsApp · 19:52 hloo how are you
 * Expanded: full paragraph text block, then ⌫ delete indicators below
 *
 * Deletion affordances live OUTSIDE this composable now: swipe the row to delete
 * one session (with a warning), or long-press to enter multi-select and delete
 * several at once. When [selectionMode] is true, tapping the card toggles its
 * selection state (via [onToggleSelected]) instead of expanding it, and a
 * checkbox is shown. Long-press always calls [onLongPress], regardless of mode,
 * so long-pressing any card (even the first one) enters multi-select.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionCard(
    session: TypingHistoryDao.TypingSession,
    events: List<TypingHistoryDao.TypingEvent>,
    maskPassword: Boolean = true,
    highlightText: String? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelected: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    // Build full paragraph text from typed + password events (joined, not split)
    // Used ONLY for the collapsed one-line preview — a quick glance at the final
    // surviving text. The expanded view below shows the full chronological replay
    // instead, including deletions in place.
    val fullParagraphText = remember(events) {
        events
            .filter {
                it.eventType == TypingHistoryDao.EventType.TYPED ||
                it.eventType == TypingHistoryDao.EventType.PASSWORD
            }
            .mapNotNull { it.textContent }
            .joinToString("")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .let {
                if (selectionMode && selected)
                    it.background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        RoundedCornerShape(12.dp)
                    )
                else it
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(visible = selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelected?.invoke() },
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = {
                            if (selectionMode) onToggleSelected?.invoke() else expanded = !expanded
                        },
                        onLongClick = { onLongPress?.invoke() }
                    )
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

            // ─── Expanded: chronological replay, deletions shown in red inline ───
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(150))
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                if (events.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        // Single reconstructed stream: typed text normal, deleted text
                        // inline in red strikethrough, in the exact order and position
                        // it happened. No separate "deleted N chars" summary block.
                        SessionReplayText(
                            events = events,
                            maskPassword = maskPassword,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                // Deletion is done by swiping the card or long-pressing to multi-select
                // in the list above — no per-card delete button here anymore, since a
                // second delete affordance right next to the swipe gesture just looked
                // like overlapping controls.
            }
            } // end inner Column (weight(1f))
        } // end outer Row (checkbox + content)
    }
}

/**
 * Wraps [SessionCard] with:
 *  - Swipe-to-delete: swiping the row in either direction triggers [onRequestDelete]
 *    (which is expected to show a confirmation dialog — this composable does NOT
 *    delete anything itself). If the swipe is not confirmed, the card animates back
 *    into place.
 *  - Long-press-to-multi-select: long-pressing any card calls [onLongPress], and
 *    while [selectionMode] is true the swipe gesture is disabled (tapping toggles
 *    selection instead, and a checkbox is shown) so the two interactions never
 *    compete with each other.
 *
 * This replaces the old per-card "Delete session" text button, which sat right next
 * to where a swipe action would happen and looked like two overlapping ways to do
 * the same thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableSessionCard(
    session: TypingHistoryDao.TypingSession,
    events: List<TypingHistoryDao.TypingEvent>,
    maskPassword: Boolean = true,
    highlightText: String? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelected: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onRequestDelete: () -> Unit
) {
    if (selectionMode) {
        // No swipe gesture while selecting — avoids two competing gestures on the
        // same row. Tap toggles selection; the checkbox is rendered by SessionCard.
        SessionCard(
            session = session,
            events = events,
            maskPassword = maskPassword,
            highlightText = highlightText,
            selectionMode = true,
            selected = selected,
            onToggleSelected = onToggleSelected,
            onLongPress = onLongPress
        )
        return
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onRequestDelete()
            }
            // Never let the box actually commit the dismiss animation itself — the
            // session is only removed once the warning dialog is confirmed, at
            // which point the caller removes it from the list and this composable
            // is discarded along with it.
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val isActive = dismissState.targetValue != SwipeToDismissBoxValue.Settled
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(
                        if (isActive) MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                        else Color.Transparent,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.StartToEnd -> Arrangement.Start
                    else -> Arrangement.End
                }
            ) {
                if (isActive) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.typing_history_delete_session),
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }
    ) {
        SessionCard(
            session = session,
            events = events,
            maskPassword = maskPassword,
            highlightText = highlightText,
            onLongPress = onLongPress
        )
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

/** A 2-hour time window with its events. */
data class TimeBucket(
    val startHour: Int,
    val endHour: Int,
    val events: List<TypingHistoryDao.TypingEvent>
) {
    // Uses the SAME formatTime() as session headers and event rows everywhere else
    // in the typing history UI. This used to build its own separate 12-hour
    // "h:mm a" formatter while every row underneath it used the 24-hour "HH:mm"
    // formatTime() — an inconsistency reported as the calendar view's time format
    // not matching the rest of the app.
    val label: String get() {
        val startCal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, startHour); set(Calendar.MINUTE, 0) }
        val endCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, endHour)
            set(Calendar.MINUTE, 0)
            if (endHour == 0) add(Calendar.DAY_OF_MONTH, 1) // midnight wrap
        }
        return "${formatTime(startCal.timeInMillis)} \u2013 ${formatTime(endCal.timeInMillis)}"
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
                    // Group this bucket's raw events by session and render ONE
                    // reconstructed line per session (same replay used in the All
                    // Recent tab), ordered by when each session started.
                    //
                    // Previously this rendered bucket.events.forEach { ... one row
                    // per raw event ... }. Because commitText is very often called
                    // once per keystroke (many apps/input fields don't batch),
                    // that meant one row — each with its own time label — per
                    // single typed character: visually the typed text appeared to
                    // be "split letter by letter", each carrying a timestamp in a
                    // format that also didn't match the rest of the app.
                    val bucketSessions = remember(bucket.events) {
                        bucket.events
                            .groupBy { it.sessionId }
                            .entries
                            .sortedBy { (_, evts) -> evts.minOf { it.timestamp } }
                    }
                    bucketSessions.forEach { (_, sessionEvents) ->
                        val firstEvent = sessionEvents.minByOrNull { it.timestamp }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (firstEvent != null) {
                                AppIcon(packageName = firstEvent.appPackage)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = firstEvent.appName ?: firstEvent.appPackage.substringAfterLast('.'),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 80.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = formatTime(firstEvent.timestamp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        SessionReplayText(
                            events = sessionEvents,
                            maskPassword = maskPassword,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.TypingHistoryDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Time formatting helper ───
private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
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
        Text(
            text = "🔑",
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        
        // Red dot indicator
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(Color(0xFFE53935), CircleShape)
        )
        
        // Reveal/hide toggle
        IconButton(
            onClick = { revealed = !revealed },
            modifier = Modifier.size(28.dp)
        ) {
            Text(
                text = if (revealed) "🙈" else "👁",
                fontSize = 12.sp
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
    val deleteColor = Color(0xFFE53935)

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
        Text(
            text = "⌫",
            fontSize = 14.sp,
            color = deleteColor,
            modifier = Modifier.padding(start = 6.dp)
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
                Text(text = "📱", fontSize = 16.sp)

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
                            .background(Color(0xFFE53935), CircleShape)
                    )
                }

                // Delete count indicator
                if (session.totalDeletes > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "⌫${session.totalDeletes}",
                        fontSize = 10.sp,
                        color = Color(0xFFE53935)
                    )
                }

                // Expand arrow
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (expanded) "▲" else "▼",
                    fontSize = 8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ─── Expanded: full text + delete indicators ───
            if (expanded) {
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
                            Text(
                                text = "⌫",
                                fontSize = 12.sp,
                                color = Color(0xFFE53935)
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

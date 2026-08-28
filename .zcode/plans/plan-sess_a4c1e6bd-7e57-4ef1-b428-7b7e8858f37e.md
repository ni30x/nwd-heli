# Typing History Feature — Final Implementation Plan (All Requirements)

## User Decisions Summary

✅ **Search**: Hidden by default, tap 🔍 icon to show  
✅ **Statistics**: No stats page needed  
✅ **Export**: Encrypted backup only (password-protected JSON)  
✅ **Sensitive app warnings**: No warnings, record everything  
✅ **Session grouping**: Smart detection — keyboard open/close, sentence completion, input field changes  
✅ **Password masking**: Show dots (••••••), tap eye icon to reveal actual text  
✅ **App icons**: Real app icons from system  
✅ **Auto-cleanup**: Keep forever, manual delete only (no auto-delete)

---

## Core Flow

```
Main Settings → Typing History Settings
                    └── [VIEW HISTORY] button
                              ↓
                    🔒 Password unlock dialog
                              ↓
                    📜 HISTORY VIEWER (main page)
                         - Smart sessions
                         - Visual event indicators
                         - Hidden search (tap 🔍)
                         - Menu (⋮): backup, manage
```

---

## Smart Session Detection (Advanced Grouping)

Instead of simple time gaps, sessions are intelligently grouped by:

### Session Boundary Triggers

| Trigger | Detection | Example |
|---|---|---|
| **App change** | Package name changes | WhatsApp → Chrome = new session |
| **Keyboard close** | `onFinishInputView()` called | User exits input field = end session |
| **Keyboard reopen** | `onStartInputView()` called | User taps new field = new session |
| **Sentence completion** | Text ends with `.!?` + space + 2s pause | Finished thought = potential session end |
| **Field cleared** | Input connection reports empty field | User cleared text = new session |
| **Input type change** | Normal → password or vice versa | Security boundary = new session |

### Session Builder Logic

```kotlin
fun shouldStartNewSession(
    currentEvent: TypingEvent,
    lastEvent: TypingEvent?,
    keyboardState: KeyboardState
): Boolean {
    if (lastEvent == null) return true
    
    // Different app = new session
    if (currentEvent.appPackage != lastEvent.appPackage) return true
    
    // Keyboard was closed and reopened = new session
    if (keyboardState.wasClosedAndReopened) return true
    
    // Input field was cleared = new session
    if (keyboardState.fieldWasCleared) return true
    
    // Input type changed (normal ↔ password) = new session
    if (currentEvent.inputType != lastEvent.inputType) return true
    
    // Sentence ended + 2+ second pause = new session
    if (lastEvent.isSentenceEnd && 
        (currentEvent.timestamp - lastEvent.timestamp) > 2000) {
        return true
    }
    
    return false
}
```

This creates **natural session boundaries** that match your actual typing behavior.

---

## History Viewer Page — Main UI

```
┌──────────────────────────────────────────────────┐
│ ← Typing History          [🔍 hidden]       ⋮    │  tap 🔍 shows search bar
├──────────────────────────────────────────────────┤
│ [All] [Typed] [Passwords] [Deletes] [Breaks]     │  event type filters
│ [Today ▾] [Apps ▾]                               │  date/app filters
├──────────────────────────────────────────────────┤
│ Today · 14:32                                    │  sticky date header
│  ┌────────────────────────────────────────────┐  │
│  │ [📱] WhatsApp              2m ago · 14:32  │  │  real app icon
│  │ ──────────────────────────────────────────│  │
│  │ 14:32:10  ok meet me at 5                 │  │  typed (plain)
│  │ 14:32:12  also send the file              │  │  typed (plain)
│  │ 14:32:13  ────────── ↵ ──────────         │  │  line break
│  │ 14:32:15  wifi password is                │  │  typed (plain)
│  │ 14:32:16  ••••••••••  🔑 [·] 👁           │  │  password (masked + red dot + reveal)
│  │ 14:32:18  ⌫ deleted "hello"               │  │  delete (gray)
│  │ 14:32:19  mySecret12                      │  │  typed (plain)
│  └────────────────────────────────────────────┘  │
│                                                  │
│  ┌────────────────────────────────────────────┐  │
│  │ [🌐] Chrome                    5m ago      │  │
│  │ search flights to delhi...                │  │  collapsed preview
│  │ [tap to expand]                           │  │
│  └────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘
```

### Search Bar (Hidden by Default)

When user taps 🔍 icon:

```
┌──────────────────────────────────────────────────┐
│ [×] [________________ Search...___] 🔍           │  search bar expands down
├──────────────────────────────────────────────────┤
│ Found in 3 sessions:                             │
│  Session: WhatsApp (2m ago)                      │
│  "wifi password" highlighted                     │
│  ...                                             │
└──────────────────────────────────────────────────┘
```

- **Tap [×]** to hide search bar again
- **Live filtering** as you type
- **Yellow highlights** on matching text

---

## Password Masking — Dots + Reveal

### Default (Masked)
```
14:32:16  ••••••••••  🔑 [·] 👁
```
- Password text shows as dots (`••••••••`)
- 🔑 lock icon indicates it's a password
- Small red dot [·] for visual indicator
- 👁 eye icon to reveal

### Revealed (Tap Eye)
```
14:32:16  mySecret123  🔑 [·] 👁̶
```
- Shows actual password text
- Eye icon changes to crossed eye (hidden state)
- Tap again to hide

### Implementation
```kotlin
@Composable
fun PasswordEventRow(event: TypingEvent) {
    var revealed by remember { mutableStateOf(false) }
    
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(event.timestamp), style = small, color = muted)
        Spacer(Modifier.width(8.dp))
        
        // Password text (masked or revealed)
        Text(
            text = if (revealed) event.text else "•".repeat(event.text.length),
            style = normal
        )
        
        Spacer(Modifier.width(8.dp))
        
        // Lock icon
        Icon(Icons.Default.Lock, modifier = Modifier.size(16.dp))
        
        // Red dot
        Box(
            Modifier
                .size(4.dp)
                .background(Color.Red, CircleShape)
        )
        
        // Reveal/hide button
        IconButton(onClick = { revealed = !revealed }) {
            Icon(
                if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (revealed) "Hide" else "Reveal"
            )
        }
    }
}
```

---

## App Icons — Real System Icons

### Loading Real Icons
```kotlin
fun loadAppIcon(context: Context, packageName: String): Drawable? {
    return try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        // Fallback: generic keyboard icon
        context.getDrawable(R.drawable.ic_keyboard)
    }
}

// Compose usage with Coil or Glide
@Composable
fun AppIconImage(packageName: String) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        loadAppIcon(context, packageName)
    }
    
    Image(
        bitmap = icon.toBitmap().asImageBitmap(),
        contentDescription = packageName,
        modifier = Modifier.size(32.dp).clip(CircleShape)
    )
}
```

---

## Encrypted Backup — Password-Protected Export

### Backup Format (Encrypted JSON)
```
User taps "Backup" → enters backup password → saves file
```

### File Structure (Before Encryption)
```json
{
  "version": 1,
  "backup_date": "2026-08-08T12:43:43.309Z",
  "total_sessions": 45,
  "total_events": 1523,
  "sessions": [
    {
      "session_id": "abc123",
      "app_package": "com.whatsapp",
      "app_name": "WhatsApp",
      "start_time": "2026-08-08T12:25:10.123Z",
      "end_time": "2026-08-08T12:26:45.789Z",
      "events": [
        {
          "timestamp": "2026-08-08T12:25:10.123Z",
          "event_type": "TYPED",
          "text": "hello"
        },
        {
          "timestamp": "2026-08-08T12:25:16.456Z",
          "event_type": "PASSWORD",
          "text": "mySecret123",
          "input_type": 129
        }
      ]
    }
  ]
}
```

### Encryption Process
```kotlin
class TypingHistoryBackupManager {
    
    // Encrypt backup with AES-256
    fun createEncryptedBackup(password: String): Uri {
        val jsonData = exportToJson()
        val encrypted = encrypt(jsonData, password)
        return saveToFile(encrypted, "HeliBoard_History_${timestamp}.enc")
    }
    
    // Decrypt and restore
    fun restoreFromEncryptedBackup(uri: Uri, password: String): Result<RestoreStats> {
        val encrypted = readFile(uri)
        val decrypted = decrypt(encrypted, password) // throws on wrong password
        val backupData = parseJson(decrypted)
        return importToDatabase(backupData)
    }
    
    private fun encrypt(data: String, password: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(deriveKey(password), "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        return cipher.doFinal(data.toByteArray())
    }
    
    private fun deriveKey(password: String): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, 10000, 256)
        return factory.generateSecret(spec).encoded
    }
}
```

### Backup Dialog
```
┌──────────────────────────────────────┐
│  Backup History                      │
│                                      │
│  All 1,523 events will be backed up  │
│                                      │
│  Set backup password:                │
│  [________________] 👁               │
│                                      │
│  ⚠️ Remember this password!          │
│  You'll need it to restore.          │
│                                      │
│  [Cancel]  [Create Backup]           │
└──────────────────────────────────────┘
```

### Restore Dialog
```
┌──────────────────────────────────────┐
│  Restore History                     │
│                                      │
│  Enter backup password:              │
│  [________________] 👁               │
│                                      │
│  Merge with existing or replace?     │
│  ○ Merge (keep existing + add new)   │
│  ● Replace (delete existing first)   │
│                                      │
│  [Cancel]  [Restore]                 │
└──────────────────────────────────────┘
```

---

## Session Detection — Keyboard State Tracking

### Tracking in `LatinIME.java`

```java
public class LatinIME extends InputMethodService {
    private String mCurrentSessionId = null;
    private long mLastTypingTime = 0;
    private boolean mFieldWasCleared = false;
    
    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        // Keyboard opened = new session
        mCurrentSessionId = UUID.randomUUID().toString();
        mFieldWasCleared = false;
        
        TypingHistoryRecorder.getInstance()
            .onSessionStart(mCurrentSessionId, info.packageName);
    }
    
    @Override
    public void onFinishInputView(boolean finishingInput) {
        // Keyboard closed = end session
        TypingHistoryRecorder.getInstance()
            .onSessionEnd(mCurrentSessionId);
        
        mCurrentSessionId = null;
    }
    
    @Override
    public void onUpdateSelection(
        int oldSelStart, int oldSelEnd,
        int newSelStart, int newSelEnd,
        int candidatesStart, int candidatesEnd
    ) {
        // Detect field cleared
        CharSequence text = getCurrentInputConnection().getTextBeforeCursor(1000, 0);
        if (TextUtils.isEmpty(text) && !TextUtils.isEmpty(mLastText)) {
            mFieldWasCleared = true;
            // Start new session
            mCurrentSessionId = UUID.randomUUID().toString();
        }
        mLastText = text;
    }
}
```

---

## Database Schema (Updated)

### `TYPING_HISTORY_EVENTS`
```sql
CREATE TABLE TYPING_HISTORY_EVENTS (
    ID INTEGER PRIMARY KEY AUTOINCREMENT,
    TIMESTAMP INTEGER NOT NULL,              -- milliseconds
    EVENT_TYPE TEXT NOT NULL,                -- TYPED | PASSWORD | DELETE | LINE_BREAK
    TEXT_CONTENT TEXT,                       -- actual text
    DELETED_TEXT TEXT,                       -- for DELETE events
    CHAR_COUNT INTEGER DEFAULT 0,
    APP_PACKAGE TEXT NOT NULL,
    APP_NAME TEXT,
    INPUT_TYPE INTEGER,
    SESSION_ID TEXT NOT NULL,                -- session grouping
    IS_SENTENCE_END INTEGER DEFAULT 0,       -- for smart session detection
    CREATED_AT INTEGER NOT NULL
)
```

### `TYPING_HISTORY_SESSIONS`
```sql
CREATE TABLE TYPING_HISTORY_SESSIONS (
    SESSION_ID TEXT PRIMARY KEY,
    APP_PACKAGE TEXT NOT NULL,
    START_TIME INTEGER NOT NULL,
    END_TIME INTEGER NOT NULL,
    SESSION_TRIGGER TEXT,                    -- KEYBOARD_OPEN | APP_CHANGE | FIELD_CLEAR | SENTENCE_END
    TOTAL_TYPED INTEGER DEFAULT 0,
    TOTAL_DELETES INTEGER DEFAULT 0,
    TOTAL_PASSWORDS INTEGER DEFAULT 0,
    TOTAL_LINEBREAKS INTEGER DEFAULT 0,
    HAS_PASSWORDS INTEGER DEFAULT 0,
    PREVIEW_TEXT TEXT
)
```

---

## Settings (Updated with Your Decisions)

### `Settings.java` Constants
```java
public static final String PREF_TYPING_HISTORY_ENABLED = "typing_history_enabled";
public static final String PREF_TYPING_HISTORY_PASSWORD = "typing_history_password_hash";
public static final String PREF_TYPING_HISTORY_RETENTION_DAYS = "typing_history_retention";
public static final String PREF_TYPING_HISTORY_EXCLUDE_APPS = "typing_history_exclude_apps";
public static final String PREF_TYPING_HISTORY_RECORD_PASSWORDS = "typing_history_record_passwords";
public static final String PREF_TYPING_HISTORY_MASK_PASSWORDS = "typing_history_mask_passwords";
```

### `Defaults.kt`
```kotlin
const val PREF_TYPING_HISTORY_ENABLED = false
const val PREF_TYPING_HISTORY_RETENTION_DAYS = -1         // -1 = never auto-delete
const val PREF_TYPING_HISTORY_RECORD_PASSWORDS = true     // record passwords
const val PREF_TYPING_HISTORY_MASK_PASSWORDS = true       // mask by default
```

---

## Implementation Files

### New Files (13 total)

| File | Purpose |
|---|---|
| `latin/database/TypingHistoryDao.kt` | Events + sessions tables, CRUD |
| `latin/TypingHistorySessionBuilder.kt` | Smart session grouping |
| `latin/TypingHistoryRecorder.kt` | Handles recording from input hooks |
| `latin/TypingHistorySecurityManager.kt` | Password via Keystore |
| `latin/TypingHistoryBackupManager.kt` | Encrypted backup/restore |
| `settings/screens/TypingHistorySettingsScreen.kt` | Settings page |
| `settings/screens/TypingHistoryViewerScreen.kt` | Main viewer (after unlock) |
| `settings/screens/TypingHistoryManageScreen.kt` | Delete page (from menu) |
| `settings/dialogs/TypingHistoryPasswordDialog.kt` | Unlock + backup password dialogs |
| `settings/screens/components/TypedEventRow.kt` | Normal text row |
| `settings/screens/components/PasswordEventRow.kt` | Password row (masked + reveal) |
| `settings/screens/components/DeleteEventRow.kt` | Backspace row |
| `settings/screens/components/LineBreakEventRow.kt` | Enter row |

### Modified Files (10 total)

| File | Changes |
|---|---|
| `latin/database/Database.kt` | Version 3→4, create tables |
| `latin/RichInputConnection.java` | Hook commitText |
| `latin/inputlogic/InputLogic.java` | Hook delete |
| `latin/LatinIME.java` | Session tracking (open/close/clear) |
| `latin/settings/Settings.java` | Add PREF constants |
| `latin/settings/Defaults.kt` | Add defaults |
| `settings/screens/MainSettingsScreen.kt` | Add menu item |
| `settings/SettingsNavHost.kt` | Add routes |
| `settings/SettingsWithoutKey.kt` | Add constants |
| `res/values/strings.xml` | Add strings |

---

## Implementation Order

1. **Database** — events + sessions tables
2. **Recording** — hook commitText, delete, keyboard state
3. **Smart session builder** — keyboard open/close, field clear detection
4. **Password protection** — Keystore + unlock dialog
5. **Settings screen** — enable + VIEW HISTORY button
6. **Viewer UI** — LazyColumn, session cards, event rows
7. **Event components** — TypedRow, PasswordRow (masked + reveal), DeleteRow, LineBreakRow
8. **Hidden search** — tap 🔍 to show, live filtering
9. **App icons** — load real icons from PackageManager
10. **Encrypted backup** — password-protected JSON export/restore
11. **Menu** — backup + manage (delete page)
12. **Testing** — full flow end-to-end

---

## Summary of All Features

✅ Records: typed text, passwords, deletes, line breaks  
✅ Visual indicators: plain text, 🔑+[·] for passwords, ⌫ for deletes, ↵ for breaks  
✅ Password masking: ••••••• by default, 👁 to reveal  
✅ Smart sessions: keyboard open/close, sentence end, field clear  
✅ Real app icons: from system PackageManager  
✅ Hidden search: tap 🔍 to show, yellow highlights  
✅ Encrypted backup: password-protected JSON  
✅ No auto-delete: manual cleanup only  
✅ No warnings: records everything  
✅ Password unlock: first time = set password, every time = enter password  
✅ Separate delete page: accessed from menu (⋮)

Ready to implement!
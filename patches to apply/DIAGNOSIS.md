# nwd-heli typing history — root cause diagnosis & fixes applied

Repo analyzed: `ni30x/nwd-heli` (HeliBoard fork with a custom "Typing History" feature).
All fixes below are already applied in `nwd-heli-typing-history-fixes.patch` / `modified_files/`.

---

## 1. Suggestions not working + 2. Sessions missing sometimes (same root cause)

**File:** `app/src/main/java/helium314/keyboard/latin/TypingHistoryRecorder.kt`

`endSession()` performed **synchronous SQLite work** — `dao.flushQueueSync()` followed
by `dao.getEventsBySession(sessionId)` (a full disk read) — directly on whatever
thread called it. Every call site is the IME's **main/UI thread**:

- `onStartInputView()` — fires on *every* keyboard open / app switch / field focus
- `onFieldCleared()` — fires on cursor moves that clear a field, send actions
- `onFinishInputView()` — fires when the keyboard closes

That means the keyboard's main thread was blocked on disk I/O on nearly every app
switch and field change. This is exactly the kind of stall that:
- delays or drops suggestion-strip updates (suggestions "not working"), and
- under load can get the IME process killed by the system watchdog **before** the
  in-flight session was ever flushed to disk — which is why sessions go missing
  intermittently rather than consistently.

**Fix applied:** `endSession()` now snapshots the ending session's identity
(session id, app package/name, trigger) synchronously — cheap, in-memory only —
then dispatches the actual DB work (`finalizeSession()`) to `Dispatchers.IO`.
The caller is free to immediately start the next session; `finalizeSession()`
never touches the recorder's instance fields, only the snapshot it was given, so
it's safe to run concurrently with the next session starting. Only
`onImeDestroyed()` (process teardown) still runs synchronously, as a one-time
best-effort flush before the process may die.

> **Verify on-device (needs a real profiler/Studio, not available in this
> environment):** confirm the previous main-thread stalls are gone via
> Android Studio's Profiler / `StrictMode` during rapid app-switching, and watch
> logcat for any remaining ANRs.

---

## 3. Delete history only shows "deleted 1/2/N chars"

**File:** `app/src/main/java/helium314/keyboard/latin/RichInputConnection.java`

`recordDeleteHistory()` always called
`recorder.onDeleteEvent(null, charCount, packageName, inputType)` — the
`deletedText` parameter (which the database schema and recorder already support!)
was **hardcoded to `null`**. The exact characters removed were never captured, so
the viewer could only ever show a count.

**Fix applied:** added `computeDeletedTextForHistory(beforeLength)`, which reads
the exact substring about to be removed from the already-tracked local buffers
(`mCommittedTextBeforeComposingText` + `mComposingText`) **before**
`deleteTextBeforeCursor()` mutates them — no extra IPC round-trip to the target
app needed. That real text is now passed through to `onDeleteEvent(...)`.

---

## 4. "Record delete in red, in perfect sequence and place, no extra spacing"

**File:** `app/src/main/java/helium314/keyboard/settings/screens/components/TypingHistoryEventRows.kt`

Even with real deleted text available, the old `SessionCard` rendered:
1. the **final surviving text** (typed + password events joined), then
2. a **separate list** of "deleted 'x'" / "deleted N chars" rows underneath.

There was no way to tell *where* in the text a deletion happened relative to what
was typed around it.

**Fix applied:** added `buildSessionReplay()` / `SessionReplayText`, which
replays a session's events in true chronological order (sorted by timestamp,
then event id as a tiebreaker) and appends each segment back-to-back:
- `TYPED` / `PASSWORD` → normal text (password masked if the user has masking on)
- `DELETE` → the same span, styled **red + strikethrough**, inserted exactly where
  it happened in the sequence
- `LINE_BREAK` → a `↵` marker only for real Enter presses

No extra characters, spaces, or paragraph breaks are inserted beyond what was
actually typed. Older delete events that predate this fix (no captured
`deletedText`) fall back to a compact inline `⌫N` marker rather than reverting to
a separate block, so old and new history read consistently.

The collapsed one-line preview in `SessionCard` still shows the "final text"
version for a quick glance; expanding a card now shows the full replay instead of
the old text-block + separate delete list.

---

## 5. Calendar section: inconsistent time format / "splits each letter"

**File:** `app/src/main/java/helium314/keyboard/settings/screens/components/TypingHistoryEventRows.kt`

Two separate bugs compounded here:

- **Format mismatch:** `TimeBucket.label` built its own `SimpleDateFormat("h:mm a", …)`
  (12-hour, AM/PM) for the 2-hour bucket headers, while every event row underneath
  used the file's `formatTime()` helper (`"HH:mm"`, 24-hour) — and so did the "All
  Recent" tab. Two different clocks on the same screen.
- **"Splits each letter":** `TimeBucketItem`'s expanded content did
  `bucket.events.forEach { event -> ...one row per raw event... }`. Because
  `commitText` is frequently invoked once per keystroke (many apps/fields don't
  batch commits), a single `TYPED` event is often exactly one character — so each
  bucket rendered one row **per letter**, each with its own timestamp, which is
  what was reported as "splitting each letter" with a clock next to it.

**Fix applied:**
- `TimeBucket.label` now calls the same `formatTime()` used everywhere else —
  one canonical clock format across the whole feature.
- `TimeBucketItem` now groups a bucket's raw events by `sessionId` and renders
  **one reconstructed replay line per session** (same `SessionReplayText` used in
  "All Recent"), with a single app/time header per session instead of one row per
  keystroke.

---

## 6. Excluded apps — preference existed but was completely unwired

**Files:** `Settings.java`, `TypingHistoryRecorder.kt`, new
`TypingHistoryExcludedAppsScreen.kt`, `SettingsNavHost.kt`, `TypingHistoryViewerScreen.kt`

`PREF_TYPING_HISTORY_EXCLUDE_APPS` / `readTypingHistoryExcludeApps()` and even the
`typing_history_exclude_apps` / `typing_history_exclude_apps_summary` strings
**already existed** in the codebase, but:
- nothing ever read the preference when deciding whether to record, and
- there was **no writer method** and **no UI anywhere** to set it.

**Fix applied:**
- `TypingHistoryRecorder.isAppExcluded(packageName)` now checks the (comma
  separated) preference and is called from every recording entry point
  (`onStartInputView`, `onTextCommitted`, `onDeleteEvent`, `onFieldCleared`).
  When the active app is on the list, the recorder starts no session and records
  nothing for it.
- Added `Settings.writeTypingHistoryExcludeApps(prefs, csv)`.
- Added a new screen, `TypingHistoryExcludedAppsScreen.kt`: lists installed apps
  (icon, label, package name) with a per-app switch, search box, selected apps
  sorted to the top. Wired into `SettingsNavHost` as a new route
  (`typing_history_excluded_apps`) and reachable from a new "Exclude apps" button
  in the typing-history settings panel.

**Bonus finding (not touched, flagged for you):** `TypingHistorySettingsScreen.kt`
and `TypingHistoryManageScreen.kt` are both imported in `SettingsNavHost.kt` but
**never actually routed anywhere** — `SettingsDestination.TypingHistory` opens
`TypingHistoryViewerScreen` directly, whose inline "gear" panel is the *only*
settings UI users ever see. Those two screens (614 lines combined) appear to be
orphaned/dead code from an earlier design. I left them alone since you didn't ask
about them, but it's worth deciding whether to delete them or finish wiring them
up — see the AI prompt for a suggested follow-up task.

---

## 7. Overlapping single delete button → swipe + long-press multi-select

**Files:** `TypingHistoryEventRows.kt`, `TypingHistoryViewerScreen.kt`, `strings.xml`

The only way to delete one session used to be a "Delete session" `TextButton` at
the bottom of the expanded card.

**Fix applied:**
- Removed that button entirely.
- `SessionCard` now supports a `selectionMode`/`selected`/`onToggleSelected`/
  `onLongPress` set of parameters, unified into **one** `combinedClickable` so tap
  and long-press don't fight each other.
- New `SwipeableSessionCard` wraps `SessionCard` with a Material3
  `SwipeToDismissBox`. Swiping **never deletes directly** — it calls
  `onRequestDelete()`, which shows the existing "Delete this session?" warning
  dialog; if you cancel, `confirmValueChange` returns `false` so the card animates
  back into place. While in multi-select mode, the swipe gesture is disabled (no
  competing gestures) and a checkbox is shown instead.
- Long-pressing any card selects it and enters multi-select mode. The top bar
  switches to a selection bar ("N selected", Select all / Deselect all, a delete
  icon that opens a bulk-delete warning dialog). System back exits selection mode
  instead of leaving the screen.

---

## Files touched

```
app/src/main/java/helium314/keyboard/latin/RichInputConnection.java
app/src/main/java/helium314/keyboard/latin/TypingHistoryRecorder.kt
app/src/main/java/helium314/keyboard/latin/settings/Settings.java
app/src/main/java/helium314/keyboard/settings/SettingsNavHost.kt
app/src/main/java/helium314/keyboard/settings/screens/TypingHistoryExcludedAppsScreen.kt   (new)
app/src/main/java/helium314/keyboard/settings/screens/TypingHistoryViewerScreen.kt
app/src/main/java/helium314/keyboard/settings/screens/components/TypingHistoryEventRows.kt
app/src/main/res/values/strings.xml
```

## Important caveat

This environment has no Android SDK / Gradle / Google Maven access, so these
changes were written and manually reviewed (including a brace-balance pass across
every file) but **not compiled**. Apply the patch, open in Android Studio, and
build before shipping — see `AI_FIX_PROMPT.md` for a checklist to hand to a coding
agent (or to work through yourself) to finish verification.

### Implementation Plan

1.  **Refactor `TypingHistoryViewerScreen` Content Structure:**
    -   In `TypingHistoryViewerScreen.kt`, the content area `when (viewMode)` block is nested directly inside the main `Column`.
    -   I will adjust how the content area is added to ensure it doesn't force centering when empty.

2.  **Fix `EmptyHistoryView` Positioning:**
    -   Modify `EmptyHistoryView` (lines 709-715) to remove `fillMaxSize()` and use `fillMaxWidth()` + `padding` instead of `Arrangement.Center`. This allows it to sit naturally below the header/filters instead of filling the rest of the screen and centering its contents.

3.  **Review Spacing:**
    -   Ensure no hidden `Spacer` elements or excessive padding exist between the `Row` of filter chips (line 435) and the `when` block.

# Follower and Following Row Tap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every loaded follower and following row open the displayed user's profile when tapped, while preserving the follow/unfollow button's independent action.

**Architecture:** Add one small reusable `Modifier` extension that owns the enabled row-click behavior, with guest navigation disabled. Apply it to the enclosing loaded-user row in both screens and remove the avatar-only `IconButton`, leaving the nested follow/unfollow button unchanged so it consumes its own taps.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Navigation Compose, AndroidX Compose UI tests.

## Global Constraints

- Guest users remain non-navigable.
- Loading placeholders remain non-interactive.
- Tapping the follow/unfollow button changes follow state and does not navigate.
- Do not change user loading, navigation destinations, follow state, APIs, or synchronization behavior.
- Preserve unrelated worktree changes.

---

### Task 1: Define and test the row interaction contract

**Files:**
- Create: `app/src/main/java/us/fireshare/tweet/profile/UserRowClick.kt`
- Create: `app/src/androidTest/java/us/fireshare/tweet/profile/UserRowClickTest.kt`

**Interfaces:**
- Consumes: Compose `Modifier.clickable`, `enabled: Boolean`, and an `onClick: () -> Unit` callback.
- Produces: `internal fun Modifier.userRowClickable(enabled: Boolean, onClick: () -> Unit): Modifier`.

- [ ] **Step 1: Write the failing interaction tests**

Create `UserRowClickTest.kt` with a Compose rule and three tests. The first test renders a tagged `Row` using the not-yet-created `userRowClickable` modifier and verifies a row click increments the navigation counter. The second renders a nested tagged `Button` and verifies its click increments only the button counter. The third passes `enabled = false` and verifies the row click does not navigate.

```kotlin
package us.fireshare.tweet.profile

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UserRowClickTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingRowInvokesNavigation() {
        var navigations = 0
        composeRule.setContent {
            Row(
                Modifier
                    .testTag("userRow")
                    .fillMaxWidth()
                    .userRowClickable(enabled = true) { navigations++ }
            ) { Text("User") }
        }

        composeRule.onNodeWithTag("userRow").performClick()

        assertEquals(1, navigations)
    }

    @Test
    fun tappingNestedActionDoesNotInvokeNavigation() {
        var navigations = 0
        var followActions = 0
        composeRule.setContent {
            Row(
                Modifier
                    .testTag("userRow")
                    .fillMaxWidth()
                    .userRowClickable(enabled = true) { navigations++ }
            ) {
                Text("User")
                Button(
                    modifier = Modifier.testTag("followButton"),
                    onClick = { followActions++ }
                ) { Text("Follow") }
            }
        }

        composeRule.onNodeWithTag("followButton", useUnmergedTree = true).performClick()

        assertEquals(0, navigations)
        assertEquals(1, followActions)
    }

    @Test
    fun disabledRowDoesNotInvokeNavigation() {
        var navigations = 0
        composeRule.setContent {
            Row(
                Modifier
                    .testTag("userRow")
                    .fillMaxWidth()
                    .userRowClickable(enabled = false) { navigations++ }
            ) { Text("Guest") }
        }

        composeRule.onNodeWithTag("userRow").performTouchInput { click() }

        assertEquals(0, navigations)
    }
}
```

- [ ] **Step 2: Compile the tests to verify the new contract fails first**

Run: `./gradlew :app:compileFullDebugAndroidTestKotlin`

Expected: FAIL because `userRowClickable` is unresolved.

- [ ] **Step 3: Add the minimal reusable click modifier**

Create `UserRowClick.kt`:

```kotlin
package us.fireshare.tweet.profile

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier

internal fun Modifier.userRowClickable(
    enabled: Boolean,
    onClick: () -> Unit
): Modifier = clickable(enabled = enabled, onClick = onClick)
```

- [ ] **Step 4: Compile and run the interaction tests**

Run: `./gradlew :app:compileFullDebugAndroidTestKotlin`

Expected: BUILD SUCCESSFUL.

If an Android device or emulator is available, run: `./gradlew :app:connectedFullDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=us.fireshare.tweet.profile.UserRowClickTest`

Expected: all three tests pass.

- [ ] **Step 5: Commit the interaction contract**

```bash
git add app/src/main/java/us/fireshare/tweet/profile/UserRowClick.kt app/src/androidTest/java/us/fireshare/tweet/profile/UserRowClickTest.kt
git commit -m "test: define user row tap behavior"
```

### Task 2: Apply row navigation to both list screens

**Files:**
- Modify: `app/src/main/java/us/fireshare/tweet/profile/FollowerScreen.kt:224-266`
- Modify: `app/src/main/java/us/fireshare/tweet/profile/FollowingScreen.kt:224-266`
- Test: `app/src/androidTest/java/us/fireshare/tweet/profile/UserRowClickTest.kt`

**Interfaces:**
- Consumes: `Modifier.userRowClickable(enabled: Boolean, onClick: () -> Unit)` from Task 1 and the existing `NavTweet.UserProfile(user.mid)` destination.
- Produces: Identical row-level profile navigation behavior in `FollowerItem` and `FollowingItem`.

- [ ] **Step 1: Move navigation from the avatar to each enclosing loaded-user row**

In both screen files, move `fillMaxWidth()` before the visual padding, then add `userRowClickable` immediately after it. This ordering includes the row padding in the tap target. Use the existing guest check as the enabled condition:

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .userRowClickable(enabled = !user.isGuest()) {
            navController.navigate(NavTweet.UserProfile(user.mid))
        }
        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
        .wrapContentHeight(Alignment.CenterVertically)
) {
```

Replace the avatar-only `IconButton` in both files with a non-clickable box that preserves the existing 52 dp layout and centered 48 dp avatar:

```kotlin
Box(
    modifier = Modifier.size(52.dp),
    contentAlignment = Alignment.Center
) {
    UserAvatar(user = user, size = 48)
}
```

Remove the now-unused Material 3 `IconButton` import only if it is no longer used elsewhere in each file. Keep `ToggleFollowingButton(userId, viewModel, appUserViewModel)` unchanged.

- [ ] **Step 2: Verify source consistency and formatting**

Run: `rg -n "userRowClickable|IconButton|ToggleFollowingButton" app/src/main/java/us/fireshare/tweet/profile/FollowerScreen.kt app/src/main/java/us/fireshare/tweet/profile/FollowingScreen.kt`

Expected: each file has one `userRowClickable` call, no row-avatar `IconButton`, and one unchanged `ToggleFollowingButton` call.

Run: `git diff --check`

Expected: no whitespace errors.

- [ ] **Step 3: Compile the affected debug variant and tests**

Run: `./gradlew :app:compileFullDebugKotlin :app:compileFullDebugAndroidTestKotlin`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run available automated verification**

Run: `./gradlew :app:testFullDebugUnitTest`

Expected: BUILD SUCCESSFUL with all unit tests passing.

If an Android device or emulator is available, run the focused connected test command from Task 1 and expect all three interaction tests to pass.

- [ ] **Step 5: Review the final diff and commit the UI change**

Review only these paths:

```bash
git diff -- app/src/main/java/us/fireshare/tweet/profile/FollowerScreen.kt app/src/main/java/us/fireshare/tweet/profile/FollowingScreen.kt app/src/main/java/us/fireshare/tweet/profile/UserRowClick.kt app/src/androidTest/java/us/fireshare/tweet/profile/UserRowClickTest.kt
```

Confirm that loaded rows navigate, guest/loading rows do not, and the follow button retains its own click handler. Then commit:

```bash
git add app/src/main/java/us/fireshare/tweet/profile/FollowerScreen.kt app/src/main/java/us/fireshare/tweet/profile/FollowingScreen.kt
git commit -m "Make follower rows fully tappable"
```

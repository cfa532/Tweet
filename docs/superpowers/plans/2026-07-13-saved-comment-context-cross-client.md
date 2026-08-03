# Saved Comment Context Cross-Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android and Web preserve, route, highlight, and render saved comments with their immediate parent without polluting normal profile tweet lists.

**Architecture:** Both clients consume the shared backend `parentTweetId` field and retain it in their native tweet models. Saved-list loaders treat membership as authoritative, resolve the immediate parent with an ordinary read, and reuse existing quoted-tweet presentation. Comment creation always routes to the immediate parent author's writable node.

**Tech Stack:** Kotlin, kotlinx.serialization, Room, Jetpack Compose, JUnit; TypeScript, Vue 3, Pinia, Vitest.

## Global Constraints

- `parentTweetId` is optional and always identifies the immediate parent.
- Normal reads must not trigger `refresh_tweet` or other explicit synchronization.
- A missing parent must not hide the saved comment.
- Comments must never become top-level profile tweets.
- Existing backend `get_tweet` supplies `parentTweetId`.

---

### Task 1: Android tweet model and creation contract

**Files:**
- Modify: `app/src/main/java/us/fireshare/tweet/datamodel/Tweet.kt`
- Modify: `app/src/main/java/us/fireshare/tweet/service/TweetWorker.kt`
- Test: `app/src/test/java/us/fireshare/tweet/datamodel/TweetParentTest.kt`

**Interfaces:**
- Produces: `Tweet.parentTweetId: MimeiId?`
- Consumes: `TweetWorker`'s immediate `parentTweet`

- [ ] **Step 1: Write a failing serialization/copy test**

```kotlin
@Test fun parentTweetId_survivesSerializationAndCopy() {
    val tweet = Tweet(mid = "c", authorId = "a", parentTweetId = "p")
    assertEquals("p", Json.decodeFromString<Tweet>(Json.encodeToString(tweet)).parentTweetId)
    assertEquals("p", tweet.copy(content = "updated").parentTweetId)
}
```

- [ ] **Step 2: Run the focused test and confirm it fails because the property is absent**

Run: `./gradlew testDebugUnitTest --tests '*TweetParentTest*'`

- [ ] **Step 3: Add `parentTweetId` to the data class and singleton factory/merge paths**

```kotlin
var parentTweetId: MimeiId? = null
```

- [ ] **Step 4: Set the immediate parent during queued comment/reply construction**

```kotlin
val comment = Tweet(
    mid = TW_CONST.GUEST_ID,
    authorId = appUser.mid,
    parentTweetId = parentTweet.mid,
    content = commentContent,
    attachments = attachments,
    timestamp = System.currentTimeMillis()
)
```

- [ ] **Step 5: Run the focused test and commit the Android model change**

---

### Task 2: Android comment routing and saved-list state

**Files:**
- Modify: `app/src/main/java/us/fireshare/tweet/HproseInstance.kt`
- Test: `app/src/test/java/us/fireshare/tweet/SavedCommentContractTest.kt`

**Interfaces:**
- Consumes: `Tweet.parentTweetId`
- Produces: comments uploaded through `tweet.author` writable service; authoritative bookmark/favorite flags

- [ ] **Step 1: Add failing contract tests for immediate-parent payload and saved-list flags**

```kotlin
@Test fun bookmarkMembershipMarksCommentBookmarked() {
    val flags = mutableListOf(false, false, false)
    flags[UserActions.BOOKMARK] = true
    assertTrue(flags[UserActions.BOOKMARK])
}
```

- [ ] **Step 2: Confirm the focused tests fail before helper/behavior implementation**

- [ ] **Step 3: Route `add_comment` through the immediate parent author's service**

Replace `appUser.hproseService?.runMApp(...)` with the resolved writable service for `tweet.author`, retaining `tweetid`, `tweetauthorid`, and parent host ID.

- [ ] **Step 4: Set saved-state flags from list membership**

```kotlin
val flags = (tweet.favorites ?: mutableListOf(false, false, false)).toMutableList()
while (flags.size < 3) flags.add(false)
flags[if (type == UserContentType.BOOKMARKS) UserActions.BOOKMARK else UserActions.FAVORITE] = true
tweet.favorites = flags
```

- [ ] **Step 5: Load the parent through the existing ordinary tweet read and cache it without adding profile membership**

- [ ] **Step 6: Run focused tests and compile Android**

Run: `./gradlew testDebugUnitTest assembleDebug`

---

### Task 3: Android saved-comment presentation and profile filtering

**Files:**
- Modify: `app/src/main/java/us/fireshare/tweet/profile/UserBookmarks.kt`
- Modify: `app/src/main/java/us/fireshare/tweet/profile/UserFavorites.kt`
- Modify: `app/src/main/java/us/fireshare/tweet/tweet/TweetItem.kt`
- Modify: `app/src/main/java/us/fireshare/tweet/datamodel/TweetCacheManager.kt`

**Interfaces:**
- Consumes: saved comment and cached `parentTweetId`
- Produces: quote presentation with embedded parent; top-level profile results excluding comments

- [ ] **Step 1: Add a failing pure mapping test for saved comment presentation**

- [ ] **Step 2: Pass a saved-list context into `TweetItem` and resolve `parentTweetId` as embedded content only for bookmarks/favorites**

- [ ] **Step 3: Reuse the existing quoted tweet body, placing parent content inside and comment content in the outer row**

- [ ] **Step 4: Filter `parentTweetId != null` from normal profile cache queries and stop toggle updates from creating author-profile membership**

- [ ] **Step 5: Verify missing parents fall back to regular comment rows**

- [ ] **Step 6: Run Android tests and compile task**

---

### Task 4: Web model, creation, and writable routing

**Files:**
- Modify: `/Users/cfa532/Documents/GitHub/TweetWeb/global.d.ts`
- Modify: `/Users/cfa532/Documents/GitHub/TweetWeb/src/stores/tweetStore.ts`
- Test: `/Users/cfa532/Documents/GitHub/TweetWeb/src/stores/tweetStore.savedComment.test.ts`

**Interfaces:**
- Produces: `Tweet.parentTweetId?: MimeiId`
- Consumes: immediate parent author and `resolveWritableHostIp`

- [ ] **Step 1: Write failing Vitest cases for preserved parent ID and writable-client selection**

```ts
expect(comment.parentTweetId).toBe(parent.mid)
expect(parentAuthorClient.RunMApp).toHaveBeenCalledWith('add_comment', expect.anything())
```

- [ ] **Step 2: Run the focused test and confirm failure**

Run: `npm run test:unit -- src/stores/tweetStore.savedComment.test.ts --run`

- [ ] **Step 3: Add `parentTweetId` to `Tweet` and all tweet normalization/session-cache copies**

- [ ] **Step 4: Set it in comment/reply creation and route `add_comment` through the immediate parent author's writable pooled client**

- [ ] **Step 5: Run the focused test**

---

### Task 5: Web saved-list loading and presentation

**Files:**
- Modify: `/Users/cfa532/Documents/GitHub/TweetWeb/src/stores/tweetStore.ts`
- Modify: `/Users/cfa532/Documents/GitHub/TweetWeb/src/components/UserPage.vue`
- Modify: `/Users/cfa532/Documents/GitHub/TweetWeb/src/views/TweetView.vue`
- Test: `/Users/cfa532/Documents/GitHub/TweetWeb/src/stores/tweetStore.savedComment.test.ts`

**Interfaces:**
- Consumes: `loadUserTweetsByType(userId, type)` and ordinary `fetchTweet`
- Produces: saved comment with `originalTweet` presentation data sourced from `parentTweetId`

- [ ] **Step 1: Add failing tests asserting saved membership flags and parent resolution**

```ts
expect(bookmark.favorites?.[1]).toBe(true)
expect(favorite.favorites?.[0]).toBe(true)
expect(comment.originalTweet?.mid).toBe(parent.mid)
```

- [ ] **Step 2: In `loadUserTweetsByType`, set the authoritative flag and fetch the parent using the ordinary read path**

- [ ] **Step 3: Attach the resolved parent only as saved-list quote context and reuse `TweetView`'s embedded quote branch**

- [ ] **Step 4: Exclude `parentTweetId` rows from `UserPage`'s normal top-level profile merge**

```ts
if (e.parentTweetId) return false
```

- [ ] **Step 5: Preserve regular-row fallback when parent loading returns null**

- [ ] **Step 6: Run focused tests, type-check, and production build**

Run: `npm run test:unit -- src/stores/tweetStore.savedComment.test.ts --run`

Run: `npm run type-check`

Run: `npm run build`

---

### Task 6: Cross-client review and commits

**Files:**
- Review all files modified in Tasks 1-5.

**Interfaces:**
- Confirms the canonical immediate-parent and one-level-sync contract across both clients.

- [ ] **Step 1: Review diffs for accidental recovery sync, profile insertion, or root-routing regressions**

- [ ] **Step 2: Run Android and Web verification commands fresh**

- [ ] **Step 3: Commit Android changes separately from Web changes with focused messages**

# Search Functionality Documentation

**Last Updated:** December 2024

---

## Overview

The search functionality in the Tweet app provides comprehensive search capabilities for both users and tweets, with intelligent query parsing and matching algorithms.

---

## Search Query Types

### 1. Username-Only Search (`@query`)

When a query starts with `@`, the search performs **username-only** search:

- **User Search:**
  - Performs exact match by fetching user from backend using `getUserId()` and `getUser()`
  - Performs partial search of known usernames and user names
  - Merges results with exact match prioritized first
  - **No retry or blacklist updates** during user fetching (uses `skipRetryAndBlacklist = true`)

- **Tweet Search:**
  - **Skipped** - Only user results are returned

### 2. Combined Search (non-`@` query)

When a query does **not** start with `@`, the search performs **combined** user and tweet search:

- **User Search:**
  - Performs exact match (if query contains no spaces)
  - Performs partial search of known usernames and user names
  - Matches against:
    - `username` (prefix match = score 0, contains = score 1)
    - `name` (prefix match = score 2, contains = score 3)

- **Tweet Search:**
  - Performs partial search of tweet content and title
  - Matches against:
    - `content` (prefix match = score 0, contains = score 1)
    - `title` (prefix match = score 2, contains = score 3)
  - **Excludes** author username and name from tweet search

---

## Implementation Details

### Search Flow

```kotlin
// SearchScreen.kt - SearchViewModel.submitSearch()

1. Parse query:
   - isUsernameOnly = query.startsWith("@")
   - userQuery = query.removePrefix("@").trim()

2. If isUsernameOnly:
   - Search users only (exact + partial)
   - Skip tweet search

3. If !isUsernameOnly:
   - Search users (exact + partial)
   - Search tweets (content + title only)
```

### User Matching Algorithm

**File:** `app/src/main/java/us/fireshare/tweet/datamodel/TweetCacheManager.kt`

```kotlin
private fun User.matchScore(query: String): Int? {
    val usernameValue = username?.takeIf { it.isNotBlank() }?.normalizedLowercase() ?: return null
    val nameValue = name?.normalizedLowercase() ?: ""

    return when {
        usernameValue.contains(query) -> if (usernameValue.startsWith(query)) 0 else 1
        nameValue.contains(query) -> if (nameValue.startsWith(query)) 2 else 3
        else -> null
    }
}
```

**Scoring:**
- Score 0: Username prefix match (highest priority)
- Score 1: Username contains match
- Score 2: Name prefix match
- Score 3: Name contains match (lowest priority)

### Tweet Matching Algorithm

**File:** `app/src/main/java/us/fireshare/tweet/datamodel/TweetCacheManager.kt`

```kotlin
private fun Tweet.matchScore(query: String): Int? {
    val contentValue = content?.normalizedLowercase() ?: ""
    val titleValue = title?.normalizedLowercase() ?: ""

    return when {
        contentValue.contains(query) -> if (contentValue.startsWith(query)) 0 else 1
        titleValue.contains(query) -> if (titleValue.startsWith(query)) 2 else 3
        else -> null
    }
}
```

**Scoring:**
- Score 0: Content prefix match (highest priority)
- Score 1: Content contains match
- Score 2: Title prefix match
- Score 3: Title contains match (lowest priority)

**Note:** Author username and name are **excluded** from tweet search.

### Exact User Fetching (Search Context)

**File:** `app/src/main/java/us/fireshare/tweet/service/SearchScreen.kt`

When fetching users during search operations:

```kotlin
private suspend fun fetchExactUser(query: String): User? {
    return try {
        val exactId = getUserId(query) ?: getUserId(query.lowercase())
        // Use skipRetryAndBlacklist = true for search operations
        exactId?.let { getUser(it, skipRetryAndBlacklist = true) }
    } catch (e: Exception) {
        Timber.tag("SearchViewModel").v(e, "Exact user lookup failed for query: $query")
        null
    }
}
```

**Key Behavior:**
- **No retries:** Single attempt only (`maxRetries = 1`)
- **No blacklist updates:** Success/failure does not update blacklist
- **Fast failure:** Returns `null` immediately on error

**File:** `app/src/main/java/us/fireshare/tweet/HproseInstance.kt`

The `getUser()` function supports `skipRetryAndBlacklist` parameter:

```kotlin
suspend fun getUser(
    userId: MimeiId?, 
    baseUrl: String? = appUser.baseUrl, 
    maxRetries: Int = 3, 
    forceRefresh: Boolean = false, 
    skipRetryAndBlacklist: Boolean = false
): User?
```

When `skipRetryAndBlacklist = true`:
- Skips blacklist check
- Sets `maxRetries = 1` (single attempt)
- Skips all `BlackList.recordFailure()` and `BlackList.recordSuccess()` calls
- Skips empty baseUrl retry logic

---

## UI Features

### Search Screen Components

**File:** `app/src/main/java/us/fireshare/tweet/service/SearchScreen.kt`

1. **Search Bar:**
   - Text input with clear button (when query is not empty)
   - Search icon button (when query is empty)
   - Real-time search as user types

2. **Keyboard Dismissal:**
   - Tapping anywhere outside tappable elements closes soft keyboard
   - Uses `LocalFocusManager.current.clearFocus()`

3. **Results Display:**
   - User results in "Accounts" section
   - Tweet results in "Tweets" section
   - Loading states and error handling

---

## Search Limits

- **User Results:** `USER_RESULT_LIMIT = 25`
- **Tweet Results:** `TWEET_RESULT_LIMIT = 40`

---

## Key Files

### Core Implementation
- **Search UI & ViewModel:** `app/src/main/java/us/fireshare/tweet/service/SearchScreen.kt`
- **Search Logic:** `app/src/main/java/us/fireshare/tweet/datamodel/TweetCacheManager.kt`
- **User Fetching:** `app/src/main/java/us/fireshare/tweet/HproseInstance.kt`

### iOS Equivalent
- **Search UI:** `/Users/cfa532/Documents/GitHub/Tweet-iOS/Sources/Features/Search/SearchScreen.swift`
- **Search Logic:** `/Users/cfa532/Documents/GitHub/Tweet-iOS/Sources/Core/TweetCacheManager.swift`

---

## Behavior Summary

### For `@username` queries:
1. ✅ Search users only (exact + partial)
2. ✅ Skip tweet search
3. ✅ No retry or blacklist updates during user fetch

### For non-`@` queries:
1. ✅ Search users (exact + partial) - matches username and name
2. ✅ Search tweets (partial) - matches content and title only
3. ✅ Exclude author username/name from tweet search

---

## Testing Notes

### Test Cases

1. **Username-Only Search:**
   - Query: `@john`
   - Expected: User results only, no tweet results
   - Verify: Exact match prioritized, partial matches included

2. **Combined Search:**
   - Query: `hello world`
   - Expected: Both user and tweet results
   - Verify: Users matched by username/name, tweets matched by content/title only

3. **Tweet Content Search:**
   - Query: `technology`
   - Expected: Tweets containing "technology" in content or title
   - Verify: Author username/name not considered

4. **Keyboard Dismissal:**
   - Tap outside search bar
   - Expected: Soft keyboard closes

5. **Clear Button:**
   - Type query, tap clear button
   - Expected: Query cleared, keyboard dismissed

---

## Future Enhancements

Potential improvements:
- [ ] Search history
- [ ] Search filters (date range, media type)
- [ ] Advanced search operators
- [ ] Search suggestions/autocomplete
- [ ] Search result highlighting

---

## Related Documentation

- **[TWEET_CACHING_ALGORITHM.md](TWEET_CACHING_ALGORITHM.md)** - Caching strategy for tweets and users
- **[TECHNICAL_ARCHITECTURE.md](TECHNICAL_ARCHITECTURE.md)** - Overall architecture

---

**Last Updated:** December 2024  
**Status:** ✅ Implemented and tested


# Blacklist Algorithm for Unavailable MIDs

## Purpose
To avoid repeated network requests for resources (users, tweets, etc.) that are known to be unavailable, the app maintains a persistent blacklist of MIDs (resource IDs) that have been confirmed as inaccessible.

## Algorithm

1. **On Fetch Failure**
   - When a resource fetch (e.g., getUser, getTweet) fails due to the resource not being found (e.g., 404, or specific error/exception), record the `mid` in the blacklist table with the current timestamp as both `firstDetected` and `lastChecked`.
   - If the `mid` is already in the blacklist, update its `lastChecked` timestamp.

2. **On Future Fetch Requests**
   - Before attempting to fetch a resource, check if the `mid` has been unavailable for 3+ days.
   - If it has been unavailable for 3+ days, immediately return `null` (or handle as not found) without making a network request.
   - If it has been unavailable for less than 3 days, still attempt the fetch to see if the resource has become available again.

3. **Two Views of the Blacklist**
   - **All Seen**: Complete record of every `mid` that was ever unavailable (for analytics/debugging).
   - **Active Blacklist**: Only `mid`s that have been unavailable for 3+ days (for blocking requests).

4. **Persistence**
   - The blacklist is permanent. Once a `mid` is added, it is never removed or expired automatically.
   - Manual removal is possible only via explicit user or developer action (e.g., database reset or admin tool).

## Data Model
- Table: `blacklisted_mid`
  - `mid` (String, primary key): The resource ID
  - `firstDetected` (Long): Timestamp when the resource was first found to be unavailable
  - `lastChecked` (Long): Timestamp of the most recent failed access attempt

## Usage Example
- On fetch failure:
  ```kotlin
  addToBlacklist(mid) // Helper function that handles insert/update
  ```
- Before fetch:
  ```kotlin
  if (shouldBlockMid(mid)) {
      return null // Resource has been unavailable for 3+ days
  }
  ```
- Get all blacklisted IDs (for analytics):
  ```kotlin
  val allBlacklisted = blacklistedMidDao.getAll()
  ```
- Get only active blacklist (3+ days):
  ```kotlin
  val now = System.currentTimeMillis()
  val activeBlacklist = blacklistedMidDao.getActiveBlacklist(now)
  ```

## Notes
- This approach prevents repeated failed network requests for permanently missing resources, improving efficiency and user experience.
- The blacklist is never automatically cleared. 
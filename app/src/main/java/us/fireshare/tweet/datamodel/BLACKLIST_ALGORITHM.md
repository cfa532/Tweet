# Blacklist Algorithm for Unavailable MIDs

## Purpose
To avoid repeated network requests for resources (users, tweets, etc.) that are known to be unavailable, the app maintains a persistent blacklist of MIDs (resource IDs) that tracks the last successful access time. A resource is considered "inaccessible for 3 days" only if there has been **zero successful access** during a 72-hour period.

## Algorithm

1. **On Fetch Failure**
   - When a resource fetch (e.g., getUser, getTweet) fails due to the resource not being found (e.g., 404, or specific error/exception), record the `mid` in the blacklist table.
   - If the `mid` is not in the blacklist, initialize it with current timestamp as `lastSuccessfulAccess` and `lastFailureTime`.
   - If the `mid` is already in the blacklist, update only the `lastFailureTime` (keep the `lastSuccessfulAccess` unchanged).

2. **On Future Fetch Requests**
   - Before attempting to fetch a resource, check if the `mid` has had **no successful access** for 3+ days.
   - If there has been no successful access for 3+ days, immediately return `null` (or handle as not found) without making a network request.
   - If there has been a successful access within 3 days, attempt the fetch to see if the resource is available.

3. **On Successful Access (Counter Reset)**
   - When a resource is successfully fetched from the backend (tweet loaded, user data retrieved, etc.), update the `lastSuccessfulAccess` timestamp.
   - This resets the 3-day counter - the resource is now considered "accessible" and will not be blocked for another 3 days.
   - The counter reset applies to all resource types: tweets, users, and other MIDs.

4. **Two Views of the Blacklist**
   - **All Seen**: Complete record of every `mid` that was ever accessed (for analytics/debugging).
   - **Active Blacklist**: Only `mid`s that have had no successful access for 3+ days (for blocking requests).

5. **Persistence**
   - The blacklist is persistent and tracks all resources that have been accessed.
   - Resources are never permanently blocked - they can always be revived through successful access.
   - Manual removal is possible via explicit user or developer action (e.g., database reset or admin tool).

## Data Model
- Table: `blacklisted_mid`
  - `mid` (String, primary key): The resource ID
  - `lastSuccessfulAccess` (Long): Timestamp of the last successful access (used for 3-day counter)
  - `lastFailureTime` (Long): Timestamp of the most recent failed access attempt

## Usage Example
- On fetch failure:
  ```kotlin
  addToBlacklist(mid) // Helper function that handles insert/update
  ```
- Before fetch:
  ```kotlin
  if (shouldBlockMid(mid)) {
      return null // Resource has had no successful access for 3+ days
  }
  ```
- On successful access (counter reset):
  ```kotlin
  updateSuccessfulAccess(mid) // Helper function that updates lastSuccessfulAccess timestamp
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
- This approach prevents repeated failed network requests for resources that have been consistently unavailable, improving efficiency and user experience.
- The 3-day counter resets on every successful access, ensuring that resources that become available again are immediately accessible.
- The algorithm tracks "zero successful access for 3 days" rather than "unavailable for 3 days", which is more accurate for determining when to stop retrying.
- Even one successful access during the 3-day period resets the counter, allowing for intermittent availability.
- The algorithm balances efficiency (avoiding repeated failed requests) with resilience (allowing recovery from temporary issues). 
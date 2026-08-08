# Repository Instructions

- Before changing code, consider multiple plausible fixes and choose the one with the smallest coherent scope.
- Prefer removing or simplifying conflicting logic before adding new state, variables, flags, or branches. Minus first, addition second.
- When a fix needs new code, keep it directly tied to the observed bug and avoid broad refactors unless they are required for correctness.
- Find root cause of a bug first before fixing it.
- Fix the root cause and stop there. Never pair a fix with a "self-heal", sanity filter, or defensive fallback that tolerates or repairs the bad state at the read site — if the fix is correct the guard is dead weight, and if it is wrong the guard hides it. When data already written by the old bug is still on disk, say so and let the user decide (clear cache, migration, or nothing) instead of coding around it.
- Review the user's requested change before implementing it. If it may remove important recovery behavior, degrade reliability, or create other negative side effects, call that out and challenge the request before editing.
- After refactoring any code, review the finished change, especially what the refactor might break by checking the impact to callers of the modified code. Write comments to explain the purpose of the refactor whenever necessary.

# Related Projects

- **Canonical sync contract:** Before changing object creation, references, node routing, synchronization, profiles, tweets, comments, replies, or related APIs, read `/Users/cfa532/Documents/GitHub/TweetBackendApp/docs/LEITHER_DATA_AND_SYNC_CONTRACT.md` and preserve its cross-client invariants.

- `TweetAppBackend` is the shared backend companion project for this app and its sibling clients.
- It lives at `/Users/cfa532/Documents/GitHub/TweetBackendApp`.
- `Tweet-iOS` lives at `/Users/cfa532/Documents/GitHub/Tweet-iOS` and also accesses `TweetBackendApp`.
- `TweetWeb` lives at `/Users/cfa532/Documents/GitHub/TweetWeb` and also accesses `TweetBackendApp`.
- When changing API calls, request or response models, authentication, posting, timeline loading, media upload, or sync behavior, consider the shared backend contract and the impact across `Tweet-iOS`, `Tweet`, and `TweetWeb`.
- If backend or sibling project files are needed, ask for read access or have the user attach/open those projects too.

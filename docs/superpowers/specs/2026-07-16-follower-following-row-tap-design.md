# Follower and Following Row Tap Design

## Goal

Make each loaded user row on the Followers and Following screens open that user's profile when the user taps anywhere on the row, except for the follow/unfollow control.

## Interaction

- Tapping the avatar, identity text, join date, biography, or otherwise unused row space opens the displayed user's profile.
- Tapping the follow/unfollow button continues to change follow state and does not navigate.
- Guest users remain non-navigable, preserving the current behavior.
- Loading placeholders remain non-interactive.

## Implementation

Apply the profile-navigation click handler to the enclosing loaded-user `Row` in both `FollowerItem` and `FollowingItem`. Remove the avatar-only `IconButton` wrapper and render the avatar at its existing visual size. Retain the nested follow/unfollow button and its existing click handler.

This is a presentation-only change. It does not alter user loading, navigation destinations, follow state, APIs, or synchronization behavior.

## Verification

- Add focused regression coverage for the row-level navigation contract where practical in the existing test setup.
- Compile the affected Android source and run the relevant test suite.
- Confirm both screens use the same row behavior and retain the independent follow/unfollow action.

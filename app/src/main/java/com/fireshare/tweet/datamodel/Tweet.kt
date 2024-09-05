package com.fireshare.tweet.datamodel

import kotlinx.serialization.Serializable

typealias MimeiId = String      // 27 or 64 character long string

object TW_CONST {
    const val GUEST_ID = "000000000000000000000000000"      // 27
}
object UserFavorites {
    const val LIKE_TWEET = 0
    const val BOOKMARK = 1
    const val RETWEET = 2
}

@Serializable
data class Tweet(
    var mid: MimeiId? = null,   // mid of the tweet
    val authorId: MimeiId,        // mid of the author, is also the mimei database Id
    var content: String,
    val timestamp: Long = System.currentTimeMillis(),

    var originalTweetId: MimeiId? = null, // this is retweet id of the original tweet
    var originalAuthorId: MimeiId? = null,  // authorId of the forwarded tweet

    // the following six attributes are for display only. Not stored in database.
    var author: User? = null,
    var originalAuthor: User? = null,
    var originalTweet: Tweet? = null,        // the original tweet for display only.

    // if the current user has liked or bookmarked this tweet
//    var hasLiked: Boolean? = false,
//    var hasBookmarked: Boolean? = false,
//    var hasRetweeted: Boolean? = false,
    var favorites: MutableList<Boolean>? = mutableListOf(false, false, false),

    var likeCount: Int = 0,     // Number of likes
//    var likers: List<MimeiId> = emptyList(),     // user list that liked the tweet

    var bookmarkCount: Int = 0, // Number of bookmarks
//    var bookmarks: List<MimeiId> = emptyList(), // user list that bookmarked the tweet

    // List of retweets ID, without comments.
//    var retweets: List<MimeiId> = emptyList(),
    var retweetCount: Int = 0,  // Number of retweets

    // List of comments (tweets) Id on this tweet.
//    var comments: List<MimeiId> = emptyList(),
    var commentCount: Int = 0,  // Number of comments

    // List of media IDs attached to the tweet. Max 4 items for now.
    var attachments: List<MimeiId>? = emptyList(),

    var isPrivate: Boolean = false,     // Viewable by the author only if true.
)

@Serializable
data class User(
    var keyPhrase: String? = "Testing on MacMini",      // used as MARK in creating User mimei Id
    var keyHint: String? = null,        // key phrase reminder to show user.
    var baseUrl: String? = null,        // most recent url used to access user data

    var mid: MimeiId,                   // Unique identifier for the user, and the mimei database
    var name: String? = null,
    var username: String? = null,
    var password: String? = null,       // hashed password
    var avatar: MimeiId? = null,        // Optional profile image URL
    var profile: String? = null,
    var timestamp: Long = System.currentTimeMillis(),
    var fansCount: Int = 0,
    var followingCount: Int = 0,

    // List of nodes authorized to the user to write tweets on.
    var nodeIds: List<MimeiId>? = null,
    var publicKey: String? = null,

    // List of tweet MIDs bookmarked by the user
    var fansList: List<MimeiId> = emptyList(),
    var followingList: List<MimeiId> = emptyList(),
    var bookmarkedTweets: List<MimeiId> = emptyList(),
    var likedTweets: List<MimeiId> = emptyList(),
    var repliedTweets: List<MimeiId> = emptyList(),
)
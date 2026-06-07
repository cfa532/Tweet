package us.fireshare.tweet.widget

import timber.log.Timber

object MediaLog {
    fun d(tag: String? = null, message: () -> String) {
        if (tag == null) {
            Timber.d(message())
        } else {
            Timber.tag(tag).d(message())
        }
    }
}

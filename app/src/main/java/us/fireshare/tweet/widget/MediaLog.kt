package us.fireshare.tweet.widget

import timber.log.Timber

object MediaLog {
    private const val ENABLE_VERBOSE_VIDEO_LOADING = false

    fun d(tag: String? = null, message: () -> String) {
        if (tag == "VideoLoading" && !ENABLE_VERBOSE_VIDEO_LOADING) return

        if (tag == null) {
            Timber.d(message())
        } else {
            Timber.tag(tag).d(message())
        }
    }
}

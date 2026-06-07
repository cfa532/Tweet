package us.fireshare.tweet.widget

import timber.log.Timber
import us.fireshare.tweet.BuildConfig

object MediaLog {
    private const val VERBOSE_MEDIA_LOGGING = false

    fun d(tag: String? = null, message: () -> String) {
        if (!BuildConfig.DEBUG || !VERBOSE_MEDIA_LOGGING) return

        if (tag == null) {
            Timber.d(message())
        } else {
            Timber.tag(tag).d(message())
        }
    }
}

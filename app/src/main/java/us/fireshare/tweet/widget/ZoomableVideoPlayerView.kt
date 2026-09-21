package us.fireshare.tweet.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.OverScroller
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Transforms Media3's video frame and surface together; controls stay fixed.
 * One native gesture stream owns zoom, pan and fitted-size navigation so a pinch
 * cannot also dismiss the player or advance the browser's pager.
 */
@OptIn(UnstableApi::class)
class ZoomableVideoPlayerView(context: Context) : PlayerView(context) {
    var onNavigationDrag: (Float, Float) -> Unit = { _, _ -> }
    var onNavigationEnd: (Float, Float) -> Unit = { _, _ -> }
    var onNavigationCancel: () -> Unit = {}
    var onSurfaceTap: (() -> Unit)? = null

    private val videoFrame: View = findViewById(androidx.media3.ui.R.id.exo_content_frame)
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var isDragging = false
    private var navigationBlocked = false
    private var controlTouch = false
    private var focusX = 0f
    private var focusY = 0f
    private val fling = OverScroller(context)
    private var zoomAnimator: ValueAnimator? = null
    private val itemListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = resetZoom()
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                focusX = detector.focusX
                focusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val nextZoom = (zoom * detector.scaleFactor).coerceIn(1f, 6f)
                val ratio = nextZoom / zoom
                panX = detector.focusX - width / 2f - (focusX - width / 2f - panX) * ratio
                panY = detector.focusY - height / 2f - (focusY - height / 2f - panY) * ratio
                zoom = nextZoom
                focusX = detector.focusX
                focusY = detector.focusY
                applyTransform()
                return true
            }
        }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSurfaceTap?.invoke() ?: performClick()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            navigationBlocked = true
            val target = if (zoom > 1f) 1f else 2.5f
            val tapX = e.x
            val tapY = e.y
            zoomAnimator = ValueAnimator.ofFloat(zoom, target).apply {
                duration = 180
                addUpdateListener {
                    val nextZoom = it.animatedValue as Float
                    val ratio = nextZoom / zoom
                    panX = tapX - width / 2f - (tapX - width / 2f - panX) * ratio
                    panY = tapY - height / 2f - (tapY - height / 2f - panY) * ratio
                    zoom = nextZoom
                    applyTransform()
                }
                start()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress || e2.pointerCount > 1) return true
            if (navigationBlocked) {
                if (zoom > 1f) {
                    panX -= distanceX
                    panY -= distanceY
                    applyTransform()
                }
            } else {
                isDragging = true
                // Navigation feedback moves this view; screen coordinates keep
                // that animation from feeding back into the drag distance.
                dragX = e2.rawX - (e1?.rawX ?: e2.rawX)
                dragY = e2.rawY - (e1?.rawY ?: e2.rawY)
                onNavigationDrag(dragX, dragY)
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (zoom <= 1f) return false
            fling.fling(panX.roundToInt(), panY.roundToInt(), velocityX.roundToInt(), velocityY.roundToInt(),
                -maxPanX().roundToInt(), maxPanX().roundToInt(), -maxPanY().roundToInt(), maxPanY().roundToInt())
            postInvalidateOnAnimation()
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    init {
        // Scale the aspect-ratio frame itself, not just its SurfaceView child.
        // Otherwise the video grows inside the old frame's clipping rectangle.
        clipChildren = true
        videoFrame.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) resetZoom()
        }
    }

    override fun setPlayer(player: Player?) {
        if (getPlayer() === player) return
        getPlayer()?.removeListener(itemListener)
        super.setPlayer(player)
        player?.addListener(itemListener)
        resetZoom()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            zoomAnimator?.cancel()
            fling.forceFinished(true)
            controlTouch = touchesControl(findViewById(androidx.media3.ui.R.id.exo_controller), event)
            navigationBlocked = zoom > 1f
            isDragging = false
            dragX = 0f
            dragY = 0f
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (controlTouch) {
            // Seeking and buttons receive the original stream, even while zoomed.
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return handled
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            navigationBlocked = true // Remains locked until every finger is lifted.
            onNavigationCancel()
        }
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                if (isDragging && !navigationBlocked) onNavigationEnd(dragX, dragY)
                else onNavigationCancel()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                onNavigationCancel()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun touchesControl(view: View?, event: MotionEvent): Boolean {
        if (view == null || view.visibility != View.VISIBLE || view.alpha == 0f) return false
        val rect = Rect()
        if (!view.getGlobalVisibleRect(rect) || !rect.contains(event.rawX.toInt(), event.rawY.toInt())) return false
        if (view.isEnabled && (view.isClickable || view is TimeBar)) return true
        return view is ViewGroup && (0 until view.childCount).any { touchesControl(view.getChildAt(it), event) }
    }

    private fun maxPanX() = max(0f, (videoFrame.width * zoom - width) / 2f)
    private fun maxPanY() = max(0f, (videoFrame.height * zoom - height) / 2f)

    private fun applyTransform() {
        panX = panX.coerceIn(-maxPanX(), maxPanX())
        panY = panY.coerceIn(-maxPanY(), maxPanY())
        videoFrame.apply {
            scaleX = zoom
            scaleY = zoom
            translationX = panX
            translationY = panY
        }
    }

    private fun resetZoom() {
        zoomAnimator?.cancel()
        fling.forceFinished(true)
        zoom = 1f
        panX = 0f
        panY = 0f
        navigationBlocked = true
        applyTransform()
        onNavigationCancel()
    }

    override fun computeScroll() {
        super.computeScroll()
        if (fling.computeScrollOffset()) {
            panX = fling.currX.toFloat()
            panY = fling.currY.toFloat()
            applyTransform()
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    override fun onDetachedFromWindow() {
        zoomAnimator?.cancel()
        fling.forceFinished(true)
        super.onDetachedFromWindow()
    }
}

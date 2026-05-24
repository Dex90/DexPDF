package com.pdfeditor.app

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout

class ZoomableFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPanning = false
    private var activePointerId = -1

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = scaleFactor
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(1f, 5f)

            // Zoom towards focus point
            val focusX = detector.focusX
            val focusY = detector.focusY
            val scaleChange = scaleFactor / oldScale
            translateX = focusX - (focusX - translateX) * scaleChange
            translateY = focusY - (focusY - translateY) * scaleChange

            clampTranslation()
            applyTransform()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scaleFactor > 1.1f) {
                // Reset zoom
                scaleFactor = 1f
                translateX = 0f
                translateY = 0f
            } else {
                // Zoom to 2.5x at tap point
                val oldScale = scaleFactor
                scaleFactor = 2.5f
                val scaleChange = scaleFactor / oldScale
                translateX = e.x - (e.x - translateX) * scaleChange
                translateY = e.y - (e.y - translateY) * scaleChange
                clampTranslation()
            }
            applyTransform()
            return true
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Intercept 2-finger gestures for zoom/pan
        // Also intercept 1-finger pan when zoomed in
        if (ev.pointerCount >= 2) return true
        if (scaleFactor > 1.1f && ev.pointerCount == 1) {
            // Only intercept if we're panning (not if child needs the touch)
            // Let child handle single taps, but intercept moves when zoomed
        }
        // Let gestureDetector check for double tap
        gestureDetector.onTouchEvent(ev)
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                isPanning = scaleFactor > 1.1f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    isPanning = true
                    lastTouchX = (event.getX(0) + event.getX(1)) / 2
                    lastTouchY = (event.getY(0) + event.getY(1)) / 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning) {
                    val x: Float
                    val y: Float
                    if (event.pointerCount >= 2) {
                        x = (event.getX(0) + event.getX(1)) / 2
                        y = (event.getY(0) + event.getY(1)) / 2
                    } else {
                        x = event.x
                        y = event.y
                    }
                    translateX += x - lastTouchX
                    translateY += y - lastTouchY
                    lastTouchX = x
                    lastTouchY = y
                    clampTranslation()
                    applyTransform()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
                activePointerId = -1
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    if (remainingIndex < event.pointerCount) {
                        lastTouchX = event.getX(remainingIndex)
                        lastTouchY = event.getY(remainingIndex)
                    }
                }
            }
        }
        return true
    }

    private fun clampTranslation() {
        val maxTransX = (width * (scaleFactor - 1)) / 2
        val maxTransY = (height * (scaleFactor - 1)) / 2
        translateX = translateX.coerceIn(-maxTransX, maxTransX)
        translateY = translateY.coerceIn(-maxTransY, maxTransY)
    }

    private fun applyTransform() {
        this.pivotX = 0f
        this.pivotY = 0f
        this.scaleX = scaleFactor
        this.scaleY = scaleFactor
        this.translationX = translateX
        this.translationY = translateY
    }

    fun resetZoom() {
        scaleFactor = 1f
        translateX = 0f
        translateY = 0f
        applyTransform()
    }

    fun isZoomed(): Boolean = scaleFactor > 1.1f
}

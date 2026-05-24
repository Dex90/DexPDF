package com.pdfeditor.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class PlacementMode {
        NONE, TEXT, SIGNATURE
    }

    data class OverlayItem(
        var x: Float,
        var y: Float,
        val type: PlacementMode,
        val text: String? = null,
        var textSize: Float = 14f,
        val bitmap: Bitmap? = null,
        var scale: Float = 1f
    )

    interface OnPlacementListener {
        fun onTextPlaced(x: Float, y: Float, text: String, textSize: Float)
        fun onSignaturePlaced(x: Float, y: Float, bitmap: Bitmap)
        fun onTextPositionSelected(x: Float, y: Float)
    }

    private var placementMode = PlacementMode.NONE
    private var pendingText: String = ""
    private var pendingTextSize: Float = 14f
    private var pendingSignature: Bitmap? = null
    private val overlayItems = mutableListOf<OverlayItem>()
    private var listener: OnPlacementListener? = null

    // Drag & resize state
    private var selectedItem: OverlayItem? = null
    private var isDragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val textPaint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val selectionPaint = Paint().apply {
        color = Color.argb(100, 76, 175, 80) // green selection
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val resizeHandlePaint = Paint().apply {
        color = Color.rgb(76, 175, 80)
        style = Paint.Style.FILL
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            selectedItem?.let { item ->
                item.scale *= detector.scaleFactor
                item.scale = item.scale.coerceIn(0.3f, 5f)
                invalidate()
                return true
            }
            return false
        }
    })

    fun setOnPlacementListener(listener: OnPlacementListener) {
        this.listener = listener
    }

    fun setPlacementMode(
        mode: PlacementMode,
        text: String = "",
        textSize: Float = 14f,
        signatureBitmap: Bitmap? = null
    ) {
        placementMode = mode
        pendingText = text
        pendingTextSize = textSize
        pendingSignature = signatureBitmap
        selectedItem = null
        invalidate()
    }

    fun clearOverlays() {
        overlayItems.clear()
        selectedItem = null
        invalidate()
    }

    fun getOverlayItems(): List<OverlayItem> = overlayItems.toList()

    fun addTextAt(x: Float, y: Float, text: String, textSize: Float) {
        val item = OverlayItem(
            x = x, y = y,
            type = PlacementMode.TEXT,
            text = text,
            textSize = textSize
        )
        overlayItems.add(item)
        selectedItem = item
        placementMode = PlacementMode.NONE
        invalidate()
    }

    private fun getItemBounds(item: OverlayItem): RectF {
        return when (item.type) {
            PlacementMode.TEXT -> {
                val size = item.textSize * resources.displayMetrics.density * item.scale
                textPaint.textSize = size
                val textWidth = textPaint.measureText(item.text ?: "")
                val textHeight = size
                RectF(
                    item.x - 10f,
                    item.y - textHeight - 5f,
                    item.x + textWidth + 10f,
                    item.y + 10f
                )
            }
            PlacementMode.SIGNATURE -> {
                item.bitmap?.let { bmp ->
                    val sigWidth = 150f * resources.displayMetrics.density * item.scale
                    val sigHeight = sigWidth * bmp.height / bmp.width
                    RectF(
                        item.x - sigWidth / 2,
                        item.y - sigHeight / 2,
                        item.x + sigWidth / 2,
                        item.y + sigHeight / 2
                    )
                } ?: RectF()
            }
            else -> RectF()
        }
    }

    private fun findItemAt(x: Float, y: Float): OverlayItem? {
        // Search in reverse order (top items first)
        for (i in overlayItems.indices.reversed()) {
            val bounds = getItemBounds(overlayItems[i])
            if (bounds.contains(x, y)) {
                return overlayItems[i]
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y

                // If in placement mode, place new item
                if (placementMode != PlacementMode.NONE) {
                    when (placementMode) {
                        PlacementMode.TEXT -> {
                            listener?.onTextPositionSelected(x, y)
                        }
                        PlacementMode.SIGNATURE -> {
                            pendingSignature?.let { sig ->
                                val item = OverlayItem(
                                    x = x, y = y,
                                    type = PlacementMode.SIGNATURE,
                                    bitmap = sig
                                )
                                overlayItems.add(item)
                                selectedItem = item
                                listener?.onSignaturePlaced(x, y, sig)
                            }
                            placementMode = PlacementMode.NONE
                        }
                        else -> {}
                    }
                    invalidate()
                    return true
                }

                // Otherwise, try to select/drag existing item
                val hitItem = findItemAt(x, y)
                if (hitItem != null) {
                    selectedItem = hitItem
                    isDragging = true
                    dragOffsetX = x - hitItem.x
                    dragOffsetY = y - hitItem.y
                    invalidate()
                    return true
                } else {
                    selectedItem = null
                    invalidate()
                }

                lastTouchX = x
                lastTouchY = y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging && selectedItem != null && event.pointerCount == 1) {
                    selectedItem!!.x = event.x - dragOffsetX
                    selectedItem!!.y = event.y - dragOffsetY
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (item in overlayItems) {
            when (item.type) {
                PlacementMode.TEXT -> {
                    textPaint.textSize = item.textSize * resources.displayMetrics.density * item.scale
                    canvas.drawText(item.text ?: "", item.x, item.y, textPaint)
                }
                PlacementMode.SIGNATURE -> {
                    item.bitmap?.let { bmp ->
                        val sigWidth = 150f * resources.displayMetrics.density * item.scale
                        val sigHeight = sigWidth * bmp.height / bmp.width
                        val destRect = RectF(
                            item.x - sigWidth / 2,
                            item.y - sigHeight / 2,
                            item.x + sigWidth / 2,
                            item.y + sigHeight / 2
                        )
                        canvas.drawBitmap(bmp, null, destRect, null)
                    }
                }
                else -> {}
            }

            // Draw selection box around selected item
            if (item == selectedItem) {
                val bounds = getItemBounds(item)
                canvas.drawRect(bounds, selectionPaint)

                // Draw resize handle (bottom-right corner)
                val handleSize = 16f
                canvas.drawCircle(
                    bounds.right,
                    bounds.bottom,
                    handleSize,
                    resizeHandlePaint
                )
            }
        }

        // Draw placement hint overlay
        if (placementMode != PlacementMode.NONE) {
            val hintPaint = Paint().apply {
                color = Color.argb(40, 76, 175, 80)
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), hintPaint)
        }
    }
}

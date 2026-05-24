package com.pdfeditor.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
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
        val x: Float,
        val y: Float,
        val type: PlacementMode,
        val text: String? = null,
        val textSize: Float = 14f,
        val bitmap: Bitmap? = null
    )

    interface OnPlacementListener {
        fun onTextPlaced(x: Float, y: Float, text: String, textSize: Float)
        fun onSignaturePlaced(x: Float, y: Float, bitmap: Bitmap)
    }

    private var placementMode = PlacementMode.NONE
    private var pendingText: String = ""
    private var pendingTextSize: Float = 14f
    private var pendingSignature: Bitmap? = null
    private val overlayItems = mutableListOf<OverlayItem>()
    private var listener: OnPlacementListener? = null

    private val textPaint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
        style = Paint.Style.FILL
    }

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
        invalidate()
    }

    fun clearOverlays() {
        overlayItems.clear()
        invalidate()
    }

    fun getOverlayItems(): List<OverlayItem> = overlayItems.toList()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && placementMode != PlacementMode.NONE) {
            val x = event.x
            val y = event.y

            when (placementMode) {
                PlacementMode.TEXT -> {
                    val item = OverlayItem(
                        x = x, y = y,
                        type = PlacementMode.TEXT,
                        text = pendingText,
                        textSize = pendingTextSize
                    )
                    overlayItems.add(item)
                    listener?.onTextPlaced(x, y, pendingText, pendingTextSize)
                }
                PlacementMode.SIGNATURE -> {
                    pendingSignature?.let { sig ->
                        val item = OverlayItem(
                            x = x, y = y,
                            type = PlacementMode.SIGNATURE,
                            bitmap = sig
                        )
                        overlayItems.add(item)
                        listener?.onSignaturePlaced(x, y, sig)
                    }
                }
                else -> {}
            }

            placementMode = PlacementMode.NONE
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (item in overlayItems) {
            when (item.type) {
                PlacementMode.TEXT -> {
                    textPaint.textSize = item.textSize * resources.displayMetrics.density
                    canvas.drawText(item.text ?: "", item.x, item.y, textPaint)
                }
                PlacementMode.SIGNATURE -> {
                    item.bitmap?.let { bmp ->
                        val sigWidth = 150f * resources.displayMetrics.density
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
        }

        // Draw placement hint
        if (placementMode != PlacementMode.NONE) {
            val hintPaint = Paint().apply {
                color = Color.argb(80, 233, 69, 96) // accent with alpha
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), hintPaint)
        }
    }
}

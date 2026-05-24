package com.pdfeditor.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * OverlayView that draws annotations in PDF-bitmap coordinate space.
 * All overlay items store their positions relative to the rendered PDF bitmap.
 * The PhotoView's display matrix is used to transform drawing so annotations
 * stay aligned with the PDF when the user zooms/scrolls.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class PlacementMode {
        NONE, TEXT, SIGNATURE, HIGHLIGHT, ERASER
    }

    data class OverlayItem(
        var x: Float,          // X in bitmap coordinates
        var y: Float,          // Y in bitmap coordinates
        val type: PlacementMode,
        val text: String? = null,
        var textSize: Float = 14f,  // in sp-like units, scaled relative to bitmap
        val bitmap: Bitmap? = null,
        var scale: Float = 1f,
        var highlightPath: Path? = null,  // path in bitmap coordinates
        var highlightColor: Int = Color.YELLOW
    )

    interface OnPlacementListener {
        fun onTextPlaced(x: Float, y: Float, text: String, textSize: Float)
        fun onSignaturePlaced(x: Float, y: Float, bitmap: Bitmap)
        fun onTextPositionSelected(x: Float, y: Float)
        fun onEmptyAreaTapped()
    }

    private var placementMode = PlacementMode.NONE
    private var pendingSignature: Bitmap? = null
    private val overlayItems = mutableListOf<OverlayItem>()
    private var listener: OnPlacementListener? = null

    // Selection and interaction
    private var selectedItem: OverlayItem? = null
    private var isDragging = false
    private var isResizing = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    // Drawing state (highlight/eraser)
    private var isDrawing = false
    private var currentPath: Path? = null
    private var highlightColor: Int = Color.YELLOW

    // The display matrix from PhotoView — maps bitmap coords → screen coords
    private val displayMatrix = Matrix()
    private val inverseMatrix = Matrix()

    // PDF bitmap dimensions (needed for proper coordinate calculations)
    private var bitmapWidth = 0f
    private var bitmapHeight = 0f

    private val textPaint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val selectionPaint = Paint().apply {
        color = Color.argb(180, 76, 175, 80)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val resizeHandlePaint = Paint().apply {
        color = Color.rgb(76, 175, 80)
        style = Paint.Style.FILL
    }

    private val highlightPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 28f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val eraserPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 35f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val HANDLE_RADIUS = 12f  // in bitmap coords

    fun setHighlightColor(color: Int) {
        highlightColor = color
    }

    fun setOnPlacementListener(l: OnPlacementListener) { listener = l }

    fun setPlacementMode(mode: PlacementMode, text: String = "", textSize: Float = 14f, signatureBitmap: Bitmap? = null) {
        placementMode = mode
        pendingSignature = signatureBitmap
        selectedItem = null
        invalidate()
    }

    fun getPlacementMode(): PlacementMode = placementMode

    /**
     * Update the display matrix from PhotoView.
     * This should be called whenever the PhotoView's matrix changes (zoom, scroll).
     */
    fun updateDisplayMatrix(matrix: Matrix, bmpWidth: Float, bmpHeight: Float) {
        displayMatrix.set(matrix)
        displayMatrix.invert(inverseMatrix)
        bitmapWidth = bmpWidth
        bitmapHeight = bmpHeight
        invalidate()
    }

    fun clearOverlays() {
        overlayItems.clear()
        selectedItem = null
        invalidate()
    }

    fun getOverlayItems(): List<OverlayItem> = overlayItems.toList()

    fun getBitmapWidth(): Float = bitmapWidth
    fun getBitmapHeight(): Float = bitmapHeight

    fun addTextAt(bitmapX: Float, bitmapY: Float, text: String, textSize: Float) {
        val item = OverlayItem(x = bitmapX, y = bitmapY, type = PlacementMode.TEXT, text = text, textSize = textSize)
        overlayItems.add(item)
        selectedItem = item
        placementMode = PlacementMode.NONE
        invalidate()
    }

    /**
     * Convert screen coordinates to bitmap coordinates using the inverse matrix.
     */
    private fun screenToBitmap(screenX: Float, screenY: Float): PointF {
        val pts = floatArrayOf(screenX, screenY)
        inverseMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    /**
     * Convert bitmap coordinates to screen coordinates using the display matrix.
     */
    private fun bitmapToScreen(bitmapX: Float, bitmapY: Float): PointF {
        val pts = floatArrayOf(bitmapX, bitmapY)
        displayMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    /**
     * Get the current scale factor from the display matrix (for scaling stroke widths etc.)
     */
    private fun getCurrentScale(): Float {
        val values = FloatArray(9)
        displayMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun getItemBounds(item: OverlayItem): RectF {
        return when (item.type) {
            PlacementMode.TEXT -> {
                val size = item.textSize * item.scale
                textPaint.textSize = size
                val w = textPaint.measureText(item.text ?: "")
                RectF(item.x - 4f, item.y - size - 2f, item.x + w + 4f, item.y + 4f)
            }
            PlacementMode.SIGNATURE -> {
                item.bitmap?.let { bmp ->
                    val sigW = 150f * item.scale
                    val sigH = sigW * bmp.height / bmp.width
                    RectF(item.x - sigW / 2, item.y - sigH / 2, item.x + sigW / 2, item.y + sigH / 2)
                } ?: RectF()
            }
            else -> RectF()
        }
    }

    private fun isOnResizeHandle(item: OverlayItem, bitmapX: Float, bitmapY: Float): Boolean {
        val bounds = getItemBounds(item)
        val hx = bounds.right
        val hy = bounds.bottom
        val threshold = HANDLE_RADIUS * 2
        return (bitmapX - hx) * (bitmapX - hx) + (bitmapY - hy) * (bitmapY - hy) < threshold * threshold
    }

    private fun findItemAt(bitmapX: Float, bitmapY: Float): OverlayItem? {
        for (i in overlayItems.indices.reversed()) {
            val item = overlayItems[i]
            if (item.type == PlacementMode.TEXT || item.type == PlacementMode.SIGNATURE) {
                if (getItemBounds(item).contains(bitmapX, bitmapY)) return item
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val screenX = event.x
        val screenY = event.y
        // Convert touch to bitmap coordinates
        val bitmapPt = screenToBitmap(screenX, screenY)
        val bx = bitmapPt.x
        val by = bitmapPt.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Drawing modes (highlight/eraser) — store path in bitmap coords
                if (placementMode == PlacementMode.HIGHLIGHT || placementMode == PlacementMode.ERASER) {
                    isDrawing = true
                    currentPath = Path().apply { moveTo(bx, by) }
                    invalidate()
                    return true
                }

                // Placement modes
                if (placementMode == PlacementMode.TEXT) {
                    // Pass bitmap coordinates to listener
                    listener?.onTextPositionSelected(bx, by)
                    return true
                }
                if (placementMode == PlacementMode.SIGNATURE) {
                    pendingSignature?.let { sig ->
                        val item = OverlayItem(x = bx, y = by, type = PlacementMode.SIGNATURE, bitmap = sig)
                        overlayItems.add(item)
                        selectedItem = item
                        listener?.onSignaturePlaced(bx, by, sig)
                    }
                    placementMode = PlacementMode.NONE
                    invalidate()
                    return true
                }

                // Check resize handle first
                selectedItem?.let { sel ->
                    if (isOnResizeHandle(sel, bx, by)) {
                        isResizing = true
                        invalidate()
                        return true
                    }
                }

                // Select/drag
                val hit = findItemAt(bx, by)
                if (hit != null) {
                    selectedItem = hit
                    isDragging = true
                    dragOffsetX = bx - hit.x
                    dragOffsetY = by - hit.y
                    invalidate()
                    return true
                }

                selectedItem = null
                listener?.onEmptyAreaTapped()
                invalidate()
                return false // Let parent handle (zoom/pan)
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDrawing && currentPath != null) {
                    currentPath!!.lineTo(bx, by)
                    invalidate()
                    return true
                }
                if (isResizing && selectedItem != null) {
                    val bounds = getItemBounds(selectedItem!!)
                    val centerX = (bounds.left + bounds.right) / 2
                    val centerY = (bounds.top + bounds.bottom) / 2
                    val dist = Math.sqrt(((bx - centerX) * (bx - centerX) + (by - centerY) * (by - centerY)).toDouble()).toFloat()
                    val originalDist = Math.sqrt(((bounds.right - centerX) * (bounds.right - centerX) + (bounds.bottom - centerY) * (bounds.bottom - centerY)).toDouble()).toFloat()
                    if (originalDist > 0) {
                        selectedItem!!.scale = (selectedItem!!.scale * dist / originalDist).coerceIn(0.3f, 5f)
                    }
                    invalidate()
                    return true
                }
                if (isDragging && selectedItem != null) {
                    selectedItem!!.x = bx - dragOffsetX
                    selectedItem!!.y = by - dragOffsetY
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDrawing && currentPath != null) {
                    val item = OverlayItem(
                        x = 0f, y = 0f,
                        type = placementMode,
                        highlightPath = currentPath,
                        highlightColor = if (placementMode == PlacementMode.ERASER) Color.WHITE else highlightColor
                    )
                    overlayItems.add(item)
                    isDrawing = false
                    currentPath = null
                    invalidate()
                    return true
                }
                isDragging = false
                isResizing = false
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Apply the PhotoView's display matrix so everything we draw
        // is in bitmap coordinate space but displayed correctly on screen
        canvas.save()
        canvas.concat(displayMatrix)

        val scale = getCurrentScale()

        for (item in overlayItems) {
            when (item.type) {
                PlacementMode.TEXT -> {
                    textPaint.textSize = item.textSize * item.scale
                    canvas.drawText(item.text ?: "", item.x, item.y, textPaint)
                }
                PlacementMode.SIGNATURE -> {
                    item.bitmap?.let { bmp ->
                        val sigW = 150f * item.scale
                        val sigH = sigW * bmp.height / bmp.width
                        val rect = RectF(item.x - sigW / 2, item.y - sigH / 2, item.x + sigW / 2, item.y + sigH / 2)
                        canvas.drawBitmap(bmp, null, rect, null)
                    }
                }
                PlacementMode.HIGHLIGHT -> {
                    item.highlightPath?.let { path ->
                        highlightPaint.color = Color.argb(80, Color.red(item.highlightColor), Color.green(item.highlightColor), Color.blue(item.highlightColor))
                        // Adjust stroke width inversely to scale so it looks consistent
                        highlightPaint.strokeWidth = 28f / scale
                        canvas.drawPath(path, highlightPaint)
                    }
                }
                PlacementMode.ERASER -> {
                    item.highlightPath?.let { path ->
                        eraserPaint.strokeWidth = 35f / scale
                        canvas.drawPath(path, eraserPaint)
                    }
                }
                else -> {}
            }

            // Selection indicator
            if (item == selectedItem && (item.type == PlacementMode.TEXT || item.type == PlacementMode.SIGNATURE)) {
                val bounds = getItemBounds(item)
                selectionPaint.strokeWidth = 2f / scale
                canvas.drawRect(bounds, selectionPaint)
                canvas.drawCircle(bounds.right, bounds.bottom, HANDLE_RADIUS / scale, resizeHandlePaint)
            }
        }

        // Current drawing path (in-progress)
        currentPath?.let { path ->
            if (placementMode == PlacementMode.ERASER) {
                eraserPaint.strokeWidth = 35f / scale
                canvas.drawPath(path, eraserPaint)
            } else {
                highlightPaint.color = Color.argb(80, Color.red(highlightColor), Color.green(highlightColor), Color.blue(highlightColor))
                highlightPaint.strokeWidth = 28f / scale
                canvas.drawPath(path, highlightPaint)
            }
        }

        canvas.restore()
    }
}

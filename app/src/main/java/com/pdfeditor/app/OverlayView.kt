package com.pdfeditor.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView

/**
 * OverlayView - Touch controller only.
 * 
 * This view is transparent and captures touch events, converting them
 * to bitmap-space coordinates. All actual rendering happens directly
 * on the PDF bitmap (like Adobe Reader). This view does NOT draw anything.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class PlacementMode {
        NONE, TEXT, SIGNATURE, HIGHLIGHT, ERASER
    }

    /**
     * Annotation item stored in bitmap coordinates.
     * All coordinates are relative to the PDF bitmap, not the view.
     */
    data class OverlayItem(
        var x: Float,
        var y: Float,
        val type: PlacementMode,
        val text: String? = null,
        var textSize: Float = 14f,
        val bitmap: Bitmap? = null,
        var scale: Float = 1f,
        var highlightPath: Path? = null,
        var highlightColor: Int = Color.YELLOW
    )

    interface OnBitmapEditListener {
        fun onTextPositionSelected(bitmapX: Float, bitmapY: Float)
        fun onSignaturePlaced(bitmapX: Float, bitmapY: Float, signature: Bitmap)
        fun onHighlightStroke(path: Path)
        fun onEraserStroke(path: Path)
        fun onEmptyAreaTapped()
        fun onItemDragged(item: OverlayItem, newBitmapX: Float, newBitmapY: Float)
        fun onItemResized(item: OverlayItem, newScale: Float)
    }

    private var placementMode = PlacementMode.NONE
    private var pendingSignature: Bitmap? = null
    private var listener: OnBitmapEditListener? = null

    // Reference to the ImageView showing the PDF bitmap (for coordinate mapping)
    private var targetImageView: ImageView? = null

    // Items list (coordinates in bitmap-space) for hit-testing
    private val overlayItems = mutableListOf<OverlayItem>()

    // Selection and drag
    private var selectedItem: OverlayItem? = null
    private var isDragging = false
    private var isResizing = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    // Drawing state
    private var isDrawing = false
    private var currentPath: Path? = null // in bitmap coordinates

    private var highlightColor: Int = Color.YELLOW

    private val HANDLE_RADIUS_BITMAP = 30f // in bitmap pixels

    // --- Public API ---

    fun setTargetImageView(imageView: ImageView) {
        targetImageView = imageView
    }

    fun setOnBitmapEditListener(l: OnBitmapEditListener) {
        listener = l
    }

    fun setPlacementMode(mode: PlacementMode, signatureBitmap: Bitmap? = null) {
        placementMode = mode
        pendingSignature = signatureBitmap
        if (mode != PlacementMode.NONE) {
            selectedItem = null
        }
        invalidate()
    }

    fun setHighlightColor(color: Int) {
        highlightColor = color
    }

    fun getHighlightColor(): Int = highlightColor

    fun setOverlayItems(items: List<OverlayItem>) {
        overlayItems.clear()
        overlayItems.addAll(items)
        selectedItem = null
    }

    fun getOverlayItems(): List<OverlayItem> = overlayItems.toList()

    fun addItem(item: OverlayItem) {
        overlayItems.add(item)
        selectedItem = item
    }

    fun clearOverlays() {
        overlayItems.clear()
        selectedItem = null
        invalidate()
    }

    fun getSelectedItem(): OverlayItem? = selectedItem

    fun clearSelection() {
        selectedItem = null
        invalidate()
    }

    // --- Coordinate conversion ---

    /**
     * Convert view (touch) coordinates to bitmap coordinates,
     * accounting for ImageView scaleType (fitCenter) and PhotoView zoom/pan.
     */
    private fun viewToBitmapCoords(viewX: Float, viewY: Float): PointF? {
        val imageView = targetImageView ?: return null
        val drawable = imageView.drawable ?: return null

        val imageMatrix = imageView.imageMatrix
        val inverse = Matrix()
        if (!imageMatrix.invert(inverse)) return null

        val point = floatArrayOf(viewX, viewY)
        inverse.mapPoints(point)

        val bx = point[0]
        val by = point[1]

        // Bounds check
        val bitmapW = drawable.intrinsicWidth.toFloat()
        val bitmapH = drawable.intrinsicHeight.toFloat()
        if (bx < 0 || by < 0 || bx > bitmapW || by > bitmapH) return null

        return PointF(bx, by)
    }

    // --- Hit testing in bitmap space ---

    private fun getItemBounds(item: OverlayItem): RectF {
        return when (item.type) {
            PlacementMode.TEXT -> {
                val paint = Paint().apply {
                    textSize = item.textSize * item.scale
                    isAntiAlias = true
                }
                val w = paint.measureText(item.text ?: "")
                val h = item.textSize * item.scale
                RectF(item.x - 8f, item.y - h - 4f, item.x + w + 8f, item.y + 8f)
            }
            PlacementMode.SIGNATURE -> {
                item.bitmap?.let { bmp ->
                    val sigW = 200f * item.scale
                    val sigH = sigW * bmp.height / bmp.width
                    RectF(item.x - sigW / 2, item.y - sigH / 2, item.x + sigW / 2, item.y + sigH / 2)
                } ?: RectF()
            }
            else -> RectF()
        }
    }

    private fun isOnResizeHandle(item: OverlayItem, bx: Float, by: Float): Boolean {
        val bounds = getItemBounds(item)
        val hx = bounds.right
        val hy = bounds.bottom
        val dist = (bx - hx) * (bx - hx) + (by - hy) * (by - hy)
        return dist < HANDLE_RADIUS_BITMAP * HANDLE_RADIUS_BITMAP * 4
    }

    private fun findItemAt(bx: Float, by: Float): OverlayItem? {
        for (i in overlayItems.indices.reversed()) {
            val item = overlayItems[i]
            if (item.type == PlacementMode.TEXT || item.type == PlacementMode.SIGNATURE) {
                if (getItemBounds(item).contains(bx, by)) return item
            }
        }
        return null
    }

    // --- Touch handling ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val viewX = event.x
        val viewY = event.y
        val bitmapPoint = viewToBitmapCoords(viewX, viewY)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Drawing modes
                if (placementMode == PlacementMode.HIGHLIGHT || placementMode == PlacementMode.ERASER) {
                    if (bitmapPoint != null) {
                        isDrawing = true
                        currentPath = Path().apply { moveTo(bitmapPoint.x, bitmapPoint.y) }
                        return true
                    }
                    return false
                }

                // Text placement
                if (placementMode == PlacementMode.TEXT) {
                    if (bitmapPoint != null) {
                        listener?.onTextPositionSelected(bitmapPoint.x, bitmapPoint.y)
                    }
                    return true
                }

                // Signature placement
                if (placementMode == PlacementMode.SIGNATURE) {
                    if (bitmapPoint != null) {
                        pendingSignature?.let { sig ->
                            listener?.onSignaturePlaced(bitmapPoint.x, bitmapPoint.y, sig)
                        }
                        placementMode = PlacementMode.NONE
                    }
                    return true
                }

                // Selection / drag / resize
                if (bitmapPoint != null) {
                    // Check resize handle on selected item first
                    selectedItem?.let { sel ->
                        if (isOnResizeHandle(sel, bitmapPoint.x, bitmapPoint.y)) {
                            isResizing = true
                            return true
                        }
                    }

                    // Try to select an item
                    val hit = findItemAt(bitmapPoint.x, bitmapPoint.y)
                    if (hit != null) {
                        selectedItem = hit
                        isDragging = true
                        dragOffsetX = bitmapPoint.x - hit.x
                        dragOffsetY = bitmapPoint.y - hit.y
                        invalidate()
                        return true
                    }

                    // Empty area
                    selectedItem = null
                    listener?.onEmptyAreaTapped()
                    invalidate()
                }
                return false // Let parent (PhotoView) handle zoom/pan
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDrawing && currentPath != null && bitmapPoint != null) {
                    currentPath!!.lineTo(bitmapPoint.x, bitmapPoint.y)
                    invalidate()
                    return true
                }
                if (isResizing && selectedItem != null && bitmapPoint != null) {
                    val bounds = getItemBounds(selectedItem!!)
                    val centerX = (bounds.left + bounds.right) / 2
                    val centerY = (bounds.top + bounds.bottom) / 2
                    val dist = Math.sqrt(((bitmapPoint.x - centerX) * (bitmapPoint.x - centerX) + (bitmapPoint.y - centerY) * (bitmapPoint.y - centerY)).toDouble()).toFloat()
                    val originalDist = Math.sqrt(((bounds.right - centerX) * (bounds.right - centerX) + (bounds.bottom - centerY) * (bounds.bottom - centerY)).toDouble()).toFloat()
                    if (originalDist > 0) {
                        val newScale = (selectedItem!!.scale * dist / originalDist).coerceIn(0.3f, 5f)
                        listener?.onItemResized(selectedItem!!, newScale)
                    }
                    return true
                }
                if (isDragging && selectedItem != null && bitmapPoint != null) {
                    val newX = bitmapPoint.x - dragOffsetX
                    val newY = bitmapPoint.y - dragOffsetY
                    listener?.onItemDragged(selectedItem!!, newX, newY)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDrawing && currentPath != null) {
                    if (placementMode == PlacementMode.ERASER) {
                        listener?.onEraserStroke(currentPath!!)
                    } else {
                        listener?.onHighlightStroke(currentPath!!)
                    }
                    isDrawing = false
                    currentPath = null
                    return true
                }
                isDragging = false
                isResizing = false
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Draw selection indicators on top (the only thing this view draws).
     * Selection box + resize handle rendered in view-space based on bitmap coords.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val sel = selectedItem ?: return
        if (sel.type != PlacementMode.TEXT && sel.type != PlacementMode.SIGNATURE) return

        val imageView = targetImageView ?: return
        val imageMatrix = imageView.imageMatrix

        // Map the item bounds from bitmap-space to view-space
        val bounds = getItemBounds(sel)
        val pts = floatArrayOf(
            bounds.left, bounds.top,
            bounds.right, bounds.bottom
        )
        imageMatrix.mapPoints(pts)

        val viewBounds = RectF(pts[0], pts[1], pts[2], pts[3])

        val selectionPaint = Paint().apply {
            color = Color.argb(180, 76, 175, 80)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
        }
        canvas.drawRect(viewBounds, selectionPaint)

        val handlePaint = Paint().apply {
            color = Color.rgb(76, 175, 80)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(viewBounds.right, viewBounds.bottom, 16f, handlePaint)
    }
}

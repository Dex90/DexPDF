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
        NONE, TEXT, SIGNATURE, HIGHLIGHT, ERASER
    }

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

    interface OnPlacementListener {
        fun onTextPlaced(x: Float, y: Float, text: String, textSize: Float)
        fun onSignaturePlaced(x: Float, y: Float, bitmap: Bitmap)
        fun onTextPositionSelected(x: Float, y: Float)
    }

    private var placementMode = PlacementMode.NONE
    private var pendingSignature: Bitmap? = null
    private val overlayItems = mutableListOf<OverlayItem>()
    private var listener: OnPlacementListener? = null

    // Multi-page overlay storage
    private val pageOverlays = mutableMapOf<Int, MutableList<OverlayItem>>()
    private var currentPageIndex = 0

    // Undo/Redo stacks
    private val undoStack = mutableListOf<OverlayItem>()
    private val redoStack = mutableListOf<OverlayItem>()
    private var undoRedoListener: OnUndoRedoStateListener? = null

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

    // Zoom/Pan compensation from PhotoView
    private var zoomScale = 1f
    private var panX = 0f
    private var panY = 0f

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

    private val HANDLE_RADIUS = 20f

    // === Undo/Redo Interface ===

    interface OnUndoRedoStateListener {
        fun onUndoRedoStateChanged(canUndo: Boolean, canRedo: Boolean)
    }

    fun setOnUndoRedoStateListener(l: OnUndoRedoStateListener) {
        undoRedoListener = l
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val item = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(item)
            overlayItems.remove(item)
            notifyUndoRedoState()
            invalidate()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val item = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(item)
            overlayItems.add(item)
            notifyUndoRedoState()
            invalidate()
        }
    }

    private fun addItemWithUndo(item: OverlayItem) {
        overlayItems.add(item)
        undoStack.add(item)
        redoStack.clear() // Clear redo when new action is performed
        notifyUndoRedoState()
    }

    private fun notifyUndoRedoState() {
        undoRedoListener?.onUndoRedoStateChanged(canUndo(), canRedo())
    }

    // === Multi-page Support ===

    fun setCurrentPage(pageIndex: Int) {
        // Save current page overlays
        saveCurrentPageOverlays()
        // Switch to new page
        currentPageIndex = pageIndex
        // Load overlays for the new page
        loadPageOverlays()
        // Reset undo/redo for new page
        undoStack.clear()
        redoStack.clear()
        notifyUndoRedoState()
        selectedItem = null
        invalidate()
    }

    private fun saveCurrentPageOverlays() {
        if (overlayItems.isNotEmpty()) {
            pageOverlays[currentPageIndex] = overlayItems.toMutableList()
        } else {
            pageOverlays.remove(currentPageIndex)
        }
    }

    private fun loadPageOverlays() {
        overlayItems.clear()
        pageOverlays[currentPageIndex]?.let { saved ->
            overlayItems.addAll(saved)
        }
    }

    fun getAllPageOverlays(): Map<Int, List<OverlayItem>> {
        // Make sure current page is saved
        saveCurrentPageOverlays()
        return pageOverlays.mapValues { it.value.toList() }
    }

    fun hasAnyOverlays(): Boolean {
        saveCurrentPageOverlays()
        return pageOverlays.any { it.value.isNotEmpty() }
    }

    // === Zoom/Pan Compensation ===

    fun updateZoomState(scale: Float, translateX: Float, translateY: Float) {
        zoomScale = scale
        panX = translateX
        panY = translateY
        invalidate()
    }

    /** Convert screen touch coordinates to view-local coordinates accounting for zoom */
    private fun screenToLocal(x: Float, y: Float): Pair<Float, Float> {
        val localX = (x - panX) / zoomScale
        val localY = (y - panY) / zoomScale
        return Pair(localX, localY)
    }

    // === Public Methods ===

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

    fun clearOverlays() {
        overlayItems.clear()
        undoStack.clear()
        redoStack.clear()
        selectedItem = null
        notifyUndoRedoState()
        invalidate()
    }

    fun clearAllPages() {
        overlayItems.clear()
        pageOverlays.clear()
        undoStack.clear()
        redoStack.clear()
        selectedItem = null
        notifyUndoRedoState()
        invalidate()
    }

    fun getOverlayItems(): List<OverlayItem> = overlayItems.toList()

    fun addTextAt(x: Float, y: Float, text: String, textSize: Float) {
        val item = OverlayItem(x = x, y = y, type = PlacementMode.TEXT, text = text, textSize = textSize)
        addItemWithUndo(item)
        selectedItem = item
        placementMode = PlacementMode.NONE
        invalidate()
    }

    fun deleteSelected() {
        selectedItem?.let { item ->
            overlayItems.remove(item)
            undoStack.remove(item)
            selectedItem = null
            notifyUndoRedoState()
            invalidate()
        }
    }

    private fun getItemBounds(item: OverlayItem): RectF {
        val density = resources.displayMetrics.density
        return when (item.type) {
            PlacementMode.TEXT -> {
                val size = item.textSize * density * item.scale
                textPaint.textSize = size
                val w = textPaint.measureText(item.text ?: "")
                RectF(item.x - 8f, item.y - size - 4f, item.x + w + 8f, item.y + 8f)
            }
            PlacementMode.SIGNATURE -> {
                item.bitmap?.let { bmp ->
                    val sigW = 150f * density * item.scale
                    val sigH = sigW * bmp.height / bmp.width
                    RectF(item.x - sigW/2, item.y - sigH/2, item.x + sigW/2, item.y + sigH/2)
                } ?: RectF()
            }
            else -> RectF()
        }
    }

    private fun isOnResizeHandle(item: OverlayItem, x: Float, y: Float): Boolean {
        val bounds = getItemBounds(item)
        val hx = bounds.right
        val hy = bounds.bottom
        return (x - hx) * (x - hx) + (y - hy) * (y - hy) < HANDLE_RADIUS * HANDLE_RADIUS * 4
    }

    private fun findItemAt(x: Float, y: Float): OverlayItem? {
        for (i in overlayItems.indices.reversed()) {
            val item = overlayItems[i]
            if (item.type == PlacementMode.TEXT || item.type == PlacementMode.SIGNATURE) {
                if (getItemBounds(item).contains(x, y)) return item
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rawX = event.x
        val rawY = event.y
        // Use raw coordinates (zoom compensation handled at placement level)
        val x = rawX
        val y = rawY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Drawing modes (highlight/eraser)
                if (placementMode == PlacementMode.HIGHLIGHT || placementMode == PlacementMode.ERASER) {
                    isDrawing = true
                    currentPath = Path().apply { moveTo(x, y) }
                    invalidate()
                    return true
                }

                // Placement modes
                if (placementMode == PlacementMode.TEXT) {
                    listener?.onTextPositionSelected(x, y)
                    return true
                }
                if (placementMode == PlacementMode.SIGNATURE) {
                    pendingSignature?.let { sig ->
                        val item = OverlayItem(x = x, y = y, type = PlacementMode.SIGNATURE, bitmap = sig)
                        addItemWithUndo(item)
                        selectedItem = item
                        listener?.onSignaturePlaced(x, y, sig)
                    }
                    placementMode = PlacementMode.NONE
                    invalidate()
                    return true
                }

                // Check resize handle first
                selectedItem?.let { sel ->
                    if (isOnResizeHandle(sel, x, y)) {
                        isResizing = true
                        invalidate()
                        return true
                    }
                }

                // Select/drag
                val hit = findItemAt(x, y)
                if (hit != null) {
                    selectedItem = hit
                    isDragging = true
                    dragOffsetX = x - hit.x
                    dragOffsetY = y - hit.y
                    invalidate()
                    return true
                }

                selectedItem = null
                invalidate()
                return false // Let parent handle (zoom/pan)
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDrawing && currentPath != null) {
                    currentPath!!.lineTo(x, y)
                    invalidate()
                    return true
                }
                if (isResizing && selectedItem != null) {
                    val bounds = getItemBounds(selectedItem!!)
                    val centerX = (bounds.left + bounds.right) / 2
                    val centerY = (bounds.top + bounds.bottom) / 2
                    val dist = Math.sqrt(((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)).toDouble()).toFloat()
                    val originalDist = Math.sqrt(((bounds.right - centerX) * (bounds.right - centerX) + (bounds.bottom - centerY) * (bounds.bottom - centerY)).toDouble()).toFloat()
                    if (originalDist > 0) {
                        selectedItem!!.scale = (selectedItem!!.scale * dist / originalDist).coerceIn(0.3f, 5f)
                    }
                    invalidate()
                    return true
                }
                if (isDragging && selectedItem != null) {
                    selectedItem!!.x = x - dragOffsetX
                    selectedItem!!.y = y - dragOffsetY
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
                    addItemWithUndo(item)
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

        for (item in overlayItems) {
            when (item.type) {
                PlacementMode.TEXT -> {
                    textPaint.textSize = item.textSize * resources.displayMetrics.density * item.scale
                    canvas.drawText(item.text ?: "", item.x, item.y, textPaint)
                }
                PlacementMode.SIGNATURE -> {
                    item.bitmap?.let { bmp ->
                        val density = resources.displayMetrics.density
                        val sigW = 150f * density * item.scale
                        val sigH = sigW * bmp.height / bmp.width
                        val rect = RectF(item.x - sigW/2, item.y - sigH/2, item.x + sigW/2, item.y + sigH/2)
                        canvas.drawBitmap(bmp, null, rect, null)
                    }
                }
                PlacementMode.HIGHLIGHT -> {
                    item.highlightPath?.let { path ->
                        highlightPaint.color = Color.argb(80, Color.red(item.highlightColor), Color.green(item.highlightColor), Color.blue(item.highlightColor))
                        canvas.drawPath(path, highlightPaint)
                    }
                }
                PlacementMode.ERASER -> {
                    item.highlightPath?.let { path ->
                        canvas.drawPath(path, eraserPaint)
                    }
                }
                else -> {}
            }

            // Selection indicator
            if (item == selectedItem && (item.type == PlacementMode.TEXT || item.type == PlacementMode.SIGNATURE)) {
                val bounds = getItemBounds(item)
                canvas.drawRect(bounds, selectionPaint)
                // Resize handle
                canvas.drawCircle(bounds.right, bounds.bottom, HANDLE_RADIUS, resizeHandlePaint)
            }
        }

        // Current drawing path
        currentPath?.let { path ->
            if (placementMode == PlacementMode.ERASER) {
                canvas.drawPath(path, eraserPaint)
            } else {
                highlightPaint.color = Color.argb(80, Color.red(highlightColor), Color.green(highlightColor), Color.blue(highlightColor))
                canvas.drawPath(path, highlightPaint)
            }
        }
    }
}

package com.pdfeditor.app

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File

object PdfModifier {

    /**
     * Apply overlays to the PDF file.
     * 
     * In the new architecture, overlay coordinates are in bitmap-space (the rendered
     * PDF page bitmap). viewWidth/viewHeight are the bitmap dimensions, NOT the view dimensions.
     * This makes coordinate conversion straightforward: bitmapCoord / bitmapSize * pdfSize.
     */
    fun applyOverlays(
        pdfFile: File,
        pageIndex: Int,
        overlays: List<OverlayView.OverlayItem>,
        bitmapWidth: Float,
        bitmapHeight: Float
    ) {
        val document = PDDocument.load(pdfFile)
        val page = document.getPage(pageIndex)
        val pageWidth = page.mediaBox.width
        val pageHeight = page.mediaBox.height

        val scaleX = pageWidth / bitmapWidth
        val scaleY = pageHeight / bitmapHeight

        val contentStream = PDPageContentStream(
            document, page, PDPageContentStream.AppendMode.APPEND, true, true
        )

        for (item in overlays) {
            when (item.type) {
                OverlayView.PlacementMode.TEXT -> {
                    item.text?.let { text ->
                        // Convert bitmap coords to PDF coords
                        val pdfX = item.x * scaleX
                        // PDF Y is inverted (origin at bottom-left)
                        val pdfY = pageHeight - (item.y * scaleY)
                        val pdfFontSize = item.textSize * item.scale * scaleY

                        contentStream.beginText()
                        contentStream.setFont(PDType1Font.HELVETICA, pdfFontSize)
                        contentStream.newLineAtOffset(pdfX, pdfY)
                        contentStream.showText(text)
                        contentStream.endText()
                    }
                }
                OverlayView.PlacementMode.SIGNATURE -> {
                    item.bitmap?.let { bitmap ->
                        val pdImage = JPEGFactory.createFromImage(document, bitmap)
                        val sigW = 200f * item.scale * scaleX
                        val sigH = sigW * bitmap.height / bitmap.width
                        val pdfX = item.x * scaleX - sigW / 2
                        val pdfY = pageHeight - (item.y * scaleY) - sigH / 2

                        contentStream.drawImage(pdImage, pdfX, pdfY, sigW, sigH)
                    }
                }
                OverlayView.PlacementMode.HIGHLIGHT -> {
                    // Highlights are burned into the bitmap for display but for PDF save
                    // we render them as semi-transparent colored rectangles along the path.
                    // For now, we flatten via bitmap approach for complex paths.
                    item.highlightPath?.let { path ->
                        applyPathAsBitmapOverlay(
                            document, page, contentStream,
                            path, item.highlightColor, 28f,
                            bitmapWidth, bitmapHeight, isEraser = false
                        )
                    }
                }
                OverlayView.PlacementMode.ERASER -> {
                    item.highlightPath?.let { path ->
                        applyPathAsBitmapOverlay(
                            document, page, contentStream,
                            path, Color.WHITE, 35f,
                            bitmapWidth, bitmapHeight, isEraser = true
                        )
                    }
                }
                else -> {}
            }
        }

        contentStream.close()
        document.save(pdfFile)
        document.close()
    }

    /**
     * For highlight/eraser paths, we render the path onto a temporary bitmap
     * and overlay it on the PDF page as an image. This preserves exact visual fidelity
     * between what the user sees and the saved PDF.
     */
    private fun applyPathAsBitmapOverlay(
        document: PDDocument,
        page: com.tom_roush.pdfbox.pdmodel.PDPage,
        contentStream: PDPageContentStream,
        path: Path,
        color: Int,
        strokeWidth: Float,
        bitmapWidth: Float,
        bitmapHeight: Float,
        isEraser: Boolean
    ) {
        val pageWidth = page.mediaBox.width
        val pageHeight = page.mediaBox.height

        // Determine the bounds of the path to create a minimal bitmap
        val bounds = RectF()
        path.computeBounds(bounds, true)

        // Expand bounds by stroke width
        val expand = strokeWidth + 2f
        bounds.left = (bounds.left - expand).coerceAtLeast(0f)
        bounds.top = (bounds.top - expand).coerceAtLeast(0f)
        bounds.right = (bounds.right + expand).coerceAtMost(bitmapWidth)
        bounds.bottom = (bounds.bottom + expand).coerceAtMost(bitmapHeight)

        if (bounds.width() <= 0 || bounds.height() <= 0) return

        // Create a bitmap for just this region
        val regionW = bounds.width().toInt().coerceAtLeast(1)
        val regionH = bounds.height().toInt().coerceAtLeast(1)
        val regionBitmap = Bitmap.createBitmap(regionW, regionH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(regionBitmap)

        // Translate path to region-local coordinates
        val offsetPath = Path(path)
        offsetPath.offset(-bounds.left, -bounds.top)

        val paint = Paint().apply {
            this.color = if (isEraser) Color.WHITE else Color.argb(
                80, Color.red(color), Color.green(color), Color.blue(color)
            )
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        canvas.drawPath(offsetPath, paint)

        // Convert region to PDF image
        val pdImage = JPEGFactory.createFromImage(document, regionBitmap)

        val scaleX = pageWidth / bitmapWidth
        val scaleY = pageHeight / bitmapHeight
        val pdfX = bounds.left * scaleX
        val pdfY = pageHeight - (bounds.bottom * scaleY) // PDF Y is inverted
        val pdfW = bounds.width() * scaleX
        val pdfH = bounds.height() * scaleY

        contentStream.drawImage(pdImage, pdfX, pdfY, pdfW, pdfH)

        regionBitmap.recycle()
    }

    fun saveToDownloads(context: Context, pdfFile: File) {
        val fileName = "DexPDF_${System.currentTimeMillis()}.pdf"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                resolver.openOutputStream(it)?.use { output ->
                    pdfFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(downloadsDir, fileName)
            pdfFile.copyTo(destFile, overwrite = true)
        }
    }

    fun initPdfBox(context: Context) {
        PDFBoxResourceLoader.init(context.applicationContext)
    }
}

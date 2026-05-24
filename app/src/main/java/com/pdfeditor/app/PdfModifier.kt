package com.pdfeditor.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PathMeasure
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
     * Apply overlay annotations to the PDF.
     *
     * Coordinates in OverlayItem are in rendered-bitmap space.
     * We convert them to PDF-page space using the ratio between
     * bitmap dimensions and PDF page dimensions.
     *
     * PDF coordinate system: origin at bottom-left, Y grows upward.
     * Bitmap coordinate system: origin at top-left, Y grows downward.
     *
     * @param pdfFile The PDF file to modify
     * @param pageIndex The page index to apply overlays to
     * @param overlays List of overlay items with bitmap coordinates
     * @param bitmapWidth Width of the rendered bitmap
     * @param bitmapHeight Height of the rendered bitmap
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

        // Scale factors: bitmap coords → PDF coords
        val scaleX = pageWidth / bitmapWidth
        val scaleY = pageHeight / bitmapHeight

        val contentStream = PDPageContentStream(
            document, page, PDPageContentStream.AppendMode.APPEND, true, true
        )

        for (item in overlays) {
            when (item.type) {
                OverlayView.PlacementMode.TEXT -> {
                    item.text?.let { text ->
                        // Convert bitmap X → PDF X (same direction)
                        val pdfX = item.x * scaleX
                        // Convert bitmap Y → PDF Y (flip vertical: PDF origin is bottom-left)
                        val pdfY = pageHeight - (item.y * scaleY)

                        val fontSize = item.textSize * item.scale * scaleY

                        contentStream.beginText()
                        contentStream.setFont(PDType1Font.HELVETICA, fontSize)
                        contentStream.newLineAtOffset(pdfX, pdfY)
                        contentStream.showText(text)
                        contentStream.endText()
                    }
                }
                OverlayView.PlacementMode.SIGNATURE -> {
                    item.bitmap?.let { bitmap ->
                        val pdImage = JPEGFactory.createFromImage(document, bitmap)
                        // Signature size in bitmap space
                        val sigWBitmap = 150f * item.scale
                        val sigHBitmap = sigWBitmap * bitmap.height / bitmap.width
                        // Convert to PDF space
                        val sigWPdf = sigWBitmap * scaleX
                        val sigHPdf = sigHBitmap * scaleY
                        // Position (center of signature)
                        val pdfX = item.x * scaleX - sigWPdf / 2
                        val pdfY = pageHeight - (item.y * scaleY) - sigHPdf / 2

                        contentStream.drawImage(pdImage, pdfX, pdfY, sigWPdf, sigHPdf)
                    }
                }
                OverlayView.PlacementMode.HIGHLIGHT, OverlayView.PlacementMode.ERASER -> {
                    // For highlight/eraser paths, we sample the path and draw lines in PDF
                    item.highlightPath?.let { path ->
                        val measure = PathMeasure(path, false)
                        val length = measure.length
                        if (length > 0) {
                            val coords = FloatArray(2)
                            val step = 2f // sample every 2 pixels in bitmap space
                            var distance = 0f

                            // Set stroke properties
                            val strokeWidth = if (item.type == OverlayView.PlacementMode.ERASER) {
                                35f * scaleX
                            } else {
                                28f * scaleX
                            }

                            contentStream.setLineWidth(strokeWidth)
                            contentStream.setLineCap(1) // Round cap

                            if (item.type == OverlayView.PlacementMode.HIGHLIGHT) {
                                // Semi-transparent highlight
                                val r = android.graphics.Color.red(item.highlightColor) / 255f
                                val g = android.graphics.Color.green(item.highlightColor) / 255f
                                val b = android.graphics.Color.blue(item.highlightColor) / 255f
                                contentStream.setStrokingColor(r, g, b)
                            } else {
                                // White eraser
                                contentStream.setStrokingColor(1f, 1f, 1f)
                            }

                            // Move to start
                            measure.getPosTan(0f, coords, null)
                            val startX = coords[0] * scaleX
                            val startY = pageHeight - (coords[1] * scaleY)
                            contentStream.moveTo(startX, startY)

                            distance += step
                            while (distance < length) {
                                measure.getPosTan(distance, coords, null)
                                val px = coords[0] * scaleX
                                val py = pageHeight - (coords[1] * scaleY)
                                contentStream.lineTo(px, py)
                                distance += step
                            }

                            // End point
                            measure.getPosTan(length, coords, null)
                            val endX = coords[0] * scaleX
                            val endY = pageHeight - (coords[1] * scaleY)
                            contentStream.lineTo(endX, endY)

                            contentStream.stroke()
                        }
                    }
                }
                else -> {}
            }
        }

        contentStream.close()
        document.save(pdfFile)
        document.close()
    }

    fun saveToDownloads(context: Context, pdfFile: File) {
        val fileName = "PDFEditor_${System.currentTimeMillis()}.pdf"

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

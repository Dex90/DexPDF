package com.pdfeditor.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.io.File

object PdfModifier {

    fun applyOverlays(
        pdfFile: File,
        pageIndex: Int,
        overlays: List<OverlayView.OverlayItem>,
        viewWidth: Float,
        viewHeight: Float
    ) {
        val document = PDDocument.load(pdfFile)
        val page = document.getPage(pageIndex)
        val pageWidth = page.mediaBox.width
        val pageHeight = page.mediaBox.height

        val contentStream = PDPageContentStream(
            document, page, PDPageContentStream.AppendMode.APPEND, true, true
        )

        for (item in overlays) {
            when (item.type) {
                OverlayView.PlacementMode.TEXT -> {
                    item.text?.let { text ->
                        val pdfX = (item.x / viewWidth) * pageWidth
                        val pdfY = pageHeight - ((item.y / viewHeight) * pageHeight)
                        contentStream.beginText()
                        contentStream.setFont(PDType1Font.HELVETICA, item.textSize * item.scale)
                        contentStream.newLineAtOffset(pdfX, pdfY)
                        contentStream.showText(text)
                        contentStream.endText()
                    }
                }
                OverlayView.PlacementMode.SIGNATURE -> {
                    item.bitmap?.let { bitmap ->
                        val pdfX = (item.x / viewWidth) * pageWidth
                        val pdfY = pageHeight - ((item.y / viewHeight) * pageHeight)
                        val pdImage = JPEGFactory.createFromImage(document, bitmap)
                        val sigWidth = 150f * item.scale
                        val sigHeight = sigWidth * bitmap.height / bitmap.width
                        contentStream.drawImage(
                            pdImage,
                            pdfX - sigWidth / 2,
                            pdfY - sigHeight / 2,
                            sigWidth,
                            sigHeight
                        )
                    }
                }
                OverlayView.PlacementMode.HIGHLIGHT -> {
                    item.highlightPath?.let { path ->
                        drawPathToPdf(
                            contentStream, path, viewWidth, viewHeight,
                            pageWidth, pageHeight, item.highlightColor,
                            strokeWidth = 28f * (pageWidth / viewWidth),
                            alpha = 0.3f
                        )
                    }
                }
                OverlayView.PlacementMode.ERASER -> {
                    item.highlightPath?.let { path ->
                        drawPathToPdf(
                            contentStream, path, viewWidth, viewHeight,
                            pageWidth, pageHeight, android.graphics.Color.WHITE,
                            strokeWidth = 35f * (pageWidth / viewWidth),
                            alpha = 1.0f
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

    private fun drawPathToPdf(
        contentStream: PDPageContentStream,
        androidPath: Path,
        viewWidth: Float,
        viewHeight: Float,
        pageWidth: Float,
        pageHeight: Float,
        color: Int,
        strokeWidth: Float,
        alpha: Float
    ) {
        // Extract points from Android Path using PathMeasure
        val pathMeasure = PathMeasure(androidPath, false)
        val length = pathMeasure.length
        if (length == 0f) return

        val points = mutableListOf<Pair<Float, Float>>()
        val pos = FloatArray(2)
        val step = 2f // Sample every 2 pixels for smooth curves

        var distance = 0f
        while (distance <= length) {
            pathMeasure.getPosTan(distance, pos, null)
            // Convert view coords to PDF coords
            val pdfX = (pos[0] / viewWidth) * pageWidth
            val pdfY = pageHeight - ((pos[1] / viewHeight) * pageHeight)
            points.add(Pair(pdfX, pdfY))
            distance += step
        }

        if (points.size < 2) return

        // Set graphics state for transparency
        val gs = PDExtendedGraphicsState()
        gs.strokingAlphaConstant = alpha
        contentStream.setGraphicsStateParameters(gs)

        // Set stroke color
        val r = android.graphics.Color.red(color) / 255f
        val g = android.graphics.Color.green(color) / 255f
        val b = android.graphics.Color.blue(color) / 255f
        contentStream.setStrokingColor(r, g, b)
        contentStream.setLineWidth(strokeWidth)
        contentStream.setLineCapStyle(1) // Round cap
        contentStream.setLineJoinStyle(1) // Round join

        // Draw the path
        contentStream.moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) {
            contentStream.lineTo(points[i].first, points[i].second)
        }
        contentStream.stroke()

        // Reset alpha
        val gsReset = PDExtendedGraphicsState()
        gsReset.strokingAlphaConstant = 1.0f
        contentStream.setGraphicsStateParameters(gsReset)
    }

    fun saveToDownloads(context: Context, pdfFile: File): String {
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

        return fileName
    }

    fun initPdfBox(context: Context) {
        PDFBoxResourceLoader.init(context.applicationContext)
    }
}

package com.pdfeditor.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
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
            // Convert view coordinates to PDF coordinates
            val pdfX = (item.x / viewWidth) * pageWidth
            val pdfY = pageHeight - ((item.y / viewHeight) * pageHeight)

            when (item.type) {
                OverlayView.PlacementMode.TEXT -> {
                    item.text?.let { text ->
                        contentStream.beginText()
                        contentStream.setFont(PDType1Font.HELVETICA, item.textSize)
                        contentStream.newLineAtOffset(pdfX, pdfY)
                        contentStream.showText(text)
                        contentStream.endText()
                    }
                }
                OverlayView.PlacementMode.SIGNATURE -> {
                    item.bitmap?.let { bitmap ->
                        val pdImage = JPEGFactory.createFromImage(document, bitmap)
                        val sigWidth = 150f
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

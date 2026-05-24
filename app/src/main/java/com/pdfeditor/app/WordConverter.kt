package com.pdfeditor.app

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileOutputStream

object WordConverter {

    fun convert(context: Context, uri: Uri): File {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open file")

        val document = XWPFDocument(inputStream)
        inputStream.close()

        val pdfDocument = PDDocument()
        var currentPage = PDPage(PDRectangle.A4)
        pdfDocument.addPage(currentPage)

        val pageWidth = PDRectangle.A4.width
        val pageHeight = PDRectangle.A4.height
        val margin = 50f
        val fontSize = 12f
        val leading = fontSize * 1.5f
        val maxWidth = pageWidth - 2 * margin

        var yPosition = pageHeight - margin
        var contentStream = PDPageContentStream(pdfDocument, currentPage)
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA, fontSize)
        contentStream.newLineAtOffset(margin, yPosition)

        for (paragraph in document.paragraphs) {
            val text = paragraph.text
            if (text.isBlank()) {
                yPosition -= leading
                if (yPosition < margin) {
                    contentStream.endText()
                    contentStream.close()
                    currentPage = PDPage(PDRectangle.A4)
                    pdfDocument.addPage(currentPage)
                    contentStream = PDPageContentStream(pdfDocument, currentPage)
                    yPosition = pageHeight - margin
                    contentStream.beginText()
                    contentStream.setFont(PDType1Font.HELVETICA, fontSize)
                    contentStream.newLineAtOffset(margin, yPosition)
                }
                contentStream.newLineAtOffset(0f, -leading)
                continue
            }

            // Word wrap
            val words = text.split(" ")
            val lines = mutableListOf<String>()
            var currentLine = StringBuilder()

            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word
                    else "$currentLine $word"
                val textWidth = PDType1Font.HELVETICA.getStringWidth(testLine) / 1000 * fontSize
                if (textWidth > maxWidth && currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                    currentLine = StringBuilder(word)
                } else {
                    currentLine = StringBuilder(testLine)
                }
            }
            if (currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
            }

            for (line in lines) {
                yPosition -= leading
                if (yPosition < margin) {
                    contentStream.endText()
                    contentStream.close()
                    currentPage = PDPage(PDRectangle.A4)
                    pdfDocument.addPage(currentPage)
                    contentStream = PDPageContentStream(pdfDocument, currentPage)
                    yPosition = pageHeight - margin
                    contentStream.beginText()
                    contentStream.setFont(PDType1Font.HELVETICA, fontSize)
                    contentStream.newLineAtOffset(margin, yPosition)
                }
                contentStream.newLineAtOffset(0f, -leading)
                contentStream.showText(line)
            }
        }

        contentStream.endText()
        contentStream.close()
        document.close()

        val outputFile = File(context.cacheDir, "converted_${System.currentTimeMillis()}.pdf")
        FileOutputStream(outputFile).use { out ->
            pdfDocument.save(out)
        }
        pdfDocument.close()

        return outputFile
    }
}

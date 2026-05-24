package com.pdfeditor.app

import android.content.Context
import android.graphics.*
import java.io.File
import java.io.FileOutputStream

class SignatureManager(private val context: Context) {

    companion object {
        private const val MAX_SIGNATURES = 2
        private const val SIGNATURE_DIR = "signatures"
    }

    private fun getSignatureDir(): File {
        val dir = File(context.filesDir, SIGNATURE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSavedSignatures(): List<File> {
        val dir = getSignatureDir()
        return dir.listFiles()?.filter { it.extension == "png" }?.sortedBy { it.name } ?: emptyList()
    }

    fun canAddMore(): Boolean {
        return getSavedSignatures().size < MAX_SIGNATURES
    }

    fun saveSignature(bitmap: Bitmap): File? {
        if (!canAddMore()) return null

        val processedBitmap = removeBackground(bitmap)
        val dir = getSignatureDir()
        val count = getSavedSignatures().size
        val file = File(dir, "signature_${count + 1}.png")

        FileOutputStream(file).use { out ->
            processedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        return file
    }

    fun deleteSignature(index: Int): Boolean {
        val signatures = getSavedSignatures()
        if (index < 0 || index >= signatures.size) return false

        val result = signatures[index].delete()

        // Rename remaining files to keep order
        val remaining = getSavedSignatures()
        remaining.forEachIndexed { i, file ->
            val newFile = File(getSignatureDir(), "signature_${i + 1}.png")
            if (file.name != newFile.name) {
                file.renameTo(newFile)
            }
        }

        return result
    }

    /**
     * Rimuove lo sfondo da un'immagine della firma.
     * Analizza il colore di sfondo predominante e lo rende trasparente.
     */
    fun removeBackground(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Sample corners to determine background color
        val cornerPixels = listOf(
            source.getPixel(0, 0),
            source.getPixel(width - 1, 0),
            source.getPixel(0, height - 1),
            source.getPixel(width - 1, height - 1)
        )

        // Use the most common corner color as background
        val bgColor = cornerPixels.groupBy { it }.maxByOrNull { it.value.size }?.key
            ?: Color.WHITE

        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)

        // Threshold for background detection
        val threshold = 60

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = source.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val distance = Math.sqrt(
                    ((r - bgR) * (r - bgR) +
                            (g - bgG) * (g - bgG) +
                            (b - bgB) * (b - bgB)).toDouble()
                )

                if (distance < threshold) {
                    // Background pixel - make transparent
                    result.setPixel(x, y, Color.TRANSPARENT)
                } else {
                    // Foreground pixel - keep but darken slightly for clarity
                    result.setPixel(x, y, pixel)
                }
            }
        }

        return result
    }
}

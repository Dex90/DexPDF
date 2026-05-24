package com.pdfeditor.app

import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pdfeditor.app.databinding.ActivityMainBinding
import com.pdfeditor.app.databinding.DialogSelectSignatureBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var currentPage = 0
    private var totalPages = 0
    private var currentPdfUri: Uri? = null
    private var currentPdfFile: File? = null
    private var pendingCheckMark = false

    // The clean PDF page bitmap (no annotations)
    private var cleanPageBitmap: Bitmap? = null
    // The displayed bitmap with annotations burned in
    private var displayBitmap: Bitmap? = null

    // All annotations for the current page (in bitmap coordinates)
    private val pageAnnotations = mutableListOf<OverlayView.OverlayItem>()

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleFileOpen(it) } }

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> uri?.let { saveToUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupButtons()
        setupOverlay()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
    }

    private fun setupButtons() {
        binding.btnOpen.setOnClickListener {
            openFileLauncher.launch(arrayOf(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/msword"
            ))
        }

        binding.btnAddText.setOnClickListener {
            if (!hasFile()) return@setOnClickListener
            binding.overlayView.setPlacementMode(OverlayView.PlacementMode.TEXT)
            showToast("Tocca dove vuoi scrivere")
        }

        binding.btnAddCheck.setOnClickListener {
            if (!hasFile()) return@setOnClickListener
            pendingCheckMark = true
            binding.overlayView.setPlacementMode(OverlayView.PlacementMode.TEXT)
            showToast("Tocca dove vuoi la spunta")
        }

        binding.btnSignature.setOnClickListener {
            if (!hasFile()) return@setOnClickListener
            showSignatureDialog()
        }

        binding.btnHighlight.setOnClickListener {
            if (!hasFile()) return@setOnClickListener
            showHighlightColorPicker()
        }

        binding.btnEraser.setOnClickListener {
            if (!hasFile()) return@setOnClickListener
            binding.overlayView.setPlacementMode(OverlayView.PlacementMode.ERASER)
            showToast("Disegna sopra per cancellare")
        }

        binding.btnOcr.setOnClickListener {
            if (!hasFile()) return@setOnClickListener
            runOcr()
        }

        binding.btnSave.setOnClickListener {
            if (!hasFile()) return@setOnClickListener
            showSaveDialog()
        }

        binding.btnPrevPage.setOnClickListener {
            if (currentPage > 0) {
                commitInlineText()
                currentPage--
                renderPage()
            }
        }

        binding.btnNextPage.setOnClickListener {
            if (currentPage < totalPages - 1) {
                commitInlineText()
                currentPage++
                renderPage()
            }
        }
    }

    private fun setupOverlay() {
        // Connect OverlayView to the ImageView for coordinate mapping
        binding.overlayView.setTargetImageView(binding.pdfPageView)

        binding.overlayView.setOnBitmapEditListener(object : OverlayView.OnBitmapEditListener {
            override fun onTextPositionSelected(bitmapX: Float, bitmapY: Float) {
                if (pendingCheckMark) {
                    addCheckMark(bitmapX, bitmapY)
                    pendingCheckMark = false
                    binding.overlayView.setPlacementMode(OverlayView.PlacementMode.NONE)
                } else {
                    showInlineEditor(bitmapX, bitmapY)
                }
            }

            override fun onSignaturePlaced(bitmapX: Float, bitmapY: Float, signature: Bitmap) {
                addSignature(bitmapX, bitmapY, signature)
            }

            override fun onHighlightStroke(path: Path) {
                addHighlightStroke(path)
            }

            override fun onEraserStroke(path: Path) {
                addEraserStroke(path)
            }

            override fun onEmptyAreaTapped() {
                commitInlineText()
            }

            override fun onItemDragged(item: OverlayView.OverlayItem, newBitmapX: Float, newBitmapY: Float) {
                item.x = newBitmapX
                item.y = newBitmapY
                redrawAnnotations()
            }

            override fun onItemResized(item: OverlayView.OverlayItem, newScale: Float) {
                item.scale = newScale
                redrawAnnotations()
            }
        })
    }

    // --- Annotation operations (all in bitmap coordinates) ---

    private fun addCheckMark(bx: Float, by: Float) {
        val item = OverlayView.OverlayItem(
            x = bx, y = by,
            type = OverlayView.PlacementMode.TEXT,
            text = "X",
            textSize = 48f // bitmap-space size
        )
        pageAnnotations.add(item)
        binding.overlayView.addItem(item)
        redrawAnnotations()
    }

    private fun addTextAt(bx: Float, by: Float, text: String) {
        val item = OverlayView.OverlayItem(
            x = bx, y = by,
            type = OverlayView.PlacementMode.TEXT,
            text = text,
            textSize = 42f // bitmap-space size, good for 3x rendered PDF
        )
        pageAnnotations.add(item)
        binding.overlayView.addItem(item)
        redrawAnnotations()
    }

    private fun addSignature(bx: Float, by: Float, signature: Bitmap) {
        val item = OverlayView.OverlayItem(
            x = bx, y = by,
            type = OverlayView.PlacementMode.SIGNATURE,
            bitmap = signature
        )
        pageAnnotations.add(item)
        binding.overlayView.addItem(item)
        binding.overlayView.setPlacementMode(OverlayView.PlacementMode.NONE)
        redrawAnnotations()
    }

    private fun addHighlightStroke(path: Path) {
        val color = binding.overlayView.getHighlightColor()
        val item = OverlayView.OverlayItem(
            x = 0f, y = 0f,
            type = OverlayView.PlacementMode.HIGHLIGHT,
            highlightPath = path,
            highlightColor = color
        )
        pageAnnotations.add(item)
        redrawAnnotations()
    }

    private fun addEraserStroke(path: Path) {
        val item = OverlayView.OverlayItem(
            x = 0f, y = 0f,
            type = OverlayView.PlacementMode.ERASER,
            highlightPath = path
        )
        pageAnnotations.add(item)
        redrawAnnotations()
    }

    // --- Core rendering: burn annotations onto bitmap ---

    /**
     * Redraws all annotations onto the clean bitmap and updates the ImageView.
     * This is the "Adobe Reader" approach: annotations are part of the displayed bitmap.
     */
    private fun redrawAnnotations() {
        val clean = cleanPageBitmap ?: return

        // Create a mutable copy of the clean page
        val result = clean.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val textPaint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        val highlightPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 28f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        val eraserPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 35f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        for (item in pageAnnotations) {
            when (item.type) {
                OverlayView.PlacementMode.TEXT -> {
                    textPaint.textSize = item.textSize * item.scale
                    canvas.drawText(item.text ?: "", item.x, item.y, textPaint)
                }
                OverlayView.PlacementMode.SIGNATURE -> {
                    item.bitmap?.let { bmp ->
                        val sigW = 200f * item.scale
                        val sigH = sigW * bmp.height / bmp.width
                        val rect = RectF(
                            item.x - sigW / 2, item.y - sigH / 2,
                            item.x + sigW / 2, item.y + sigH / 2
                        )
                        canvas.drawBitmap(bmp, null, rect, null)
                    }
                }
                OverlayView.PlacementMode.HIGHLIGHT -> {
                    item.highlightPath?.let { path ->
                        highlightPaint.color = Color.argb(
                            80,
                            Color.red(item.highlightColor),
                            Color.green(item.highlightColor),
                            Color.blue(item.highlightColor)
                        )
                        canvas.drawPath(path, highlightPaint)
                    }
                }
                OverlayView.PlacementMode.ERASER -> {
                    item.highlightPath?.let { path ->
                        canvas.drawPath(path, eraserPaint)
                    }
                }
                else -> {}
            }
        }

        displayBitmap = result
        binding.pdfPageView.setImageBitmap(result)
    }

    // --- Inline text editor ---

    private var inlineBitmapX = 0f
    private var inlineBitmapY = 0f

    private fun showInlineEditor(bitmapX: Float, bitmapY: Float) {
        commitInlineText()

        inlineBitmapX = bitmapX
        inlineBitmapY = bitmapY

        // Convert bitmap coords to view coords for EditText positioning
        val imageMatrix = binding.pdfPageView.imageMatrix
        val pts = floatArrayOf(bitmapX, bitmapY)
        imageMatrix.mapPoints(pts)

        val editText = binding.inlineEditText
        val params = editText.layoutParams as android.widget.FrameLayout.LayoutParams
        params.leftMargin = pts[0].toInt()
        params.topMargin = (pts[1] - 20).toInt()
        editText.layoutParams = params
        editText.setText("")
        editText.visibility = View.VISIBLE
        editText.requestFocus()

        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

        binding.overlayView.setPlacementMode(OverlayView.PlacementMode.NONE)
    }

    private fun commitInlineText() {
        val editText = binding.inlineEditText
        if (editText.visibility == View.VISIBLE) {
            val text = editText.text.toString()
            if (text.isNotBlank()) {
                addTextAt(inlineBitmapX, inlineBitmapY, text)
            }
            editText.setText("")
            editText.visibility = View.GONE
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(editText.windowToken, 0)
        }
    }

    // --- File handling ---

    private fun hasFile(): Boolean {
        if (currentPdfFile == null) {
            showToast("Apri prima un file")
            return false
        }
        return true
    }

    private fun handleIncomingIntent(intent: Intent) {
        val uri = intent.data ?: return
        val mimeType = contentResolver.getType(uri) ?: return
        when {
            mimeType == "application/pdf" -> openPdf(uri)
            mimeType.contains("word") || mimeType.contains("document") -> convertWordToPdf(uri)
        }
    }

    private fun handleFileOpen(uri: Uri) {
        val mimeType = contentResolver.getType(uri) ?: return
        when {
            mimeType == "application/pdf" -> openPdf(uri)
            mimeType.contains("word") || mimeType.contains("document") -> convertWordToPdf(uri)
        }
    }

    private fun openPdf(uri: Uri) {
        try {
            closePdfRenderer()
            currentPdfUri = uri
            val cacheFile = File(cacheDir, "current.pdf")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
            }
            currentPdfFile = cacheFile
            fileDescriptor = ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(fileDescriptor!!)
            totalPages = pdfRenderer!!.pageCount
            currentPage = 0
            binding.emptyState.visibility = View.GONE
            pageAnnotations.clear()
            binding.overlayView.clearOverlays()
            renderPage()
        } catch (e: Exception) {
            showToast("Errore: ${e.message}")
        }
    }

    private fun openPdfFromFile(file: File) {
        try {
            closePdfRenderer()
            currentPdfFile = file
            currentPdfUri = Uri.fromFile(file)
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(fileDescriptor!!)
            totalPages = pdfRenderer!!.pageCount
            currentPage = 0
            binding.emptyState.visibility = View.GONE
            pageAnnotations.clear()
            binding.overlayView.clearOverlays()
            renderPage()
        } catch (e: Exception) {
            showToast("Errore: ${e.message}")
        }
    }

    private fun renderPage() {
        pdfRenderer?.let { renderer ->
            val page = renderer.openPage(currentPage)
            val bitmap = Bitmap.createBitmap(page.width * 3, page.height * 3, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            // Store the clean bitmap
            cleanPageBitmap = bitmap
            // Clear annotations for new page
            pageAnnotations.clear()
            binding.overlayView.clearOverlays()
            // Display clean page
            displayBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            binding.pdfPageView.setImageBitmap(displayBitmap)
            binding.tvPageIndicator.text = getString(R.string.page_indicator, currentPage + 1, totalPages)
        }
    }

    // --- Save ---

    private fun showSaveDialog() {
        val input = EditText(this)
        input.hint = "Nome file"
        input.setText("DexPDF_${System.currentTimeMillis()}")
        input.setPadding(48, 32, 48, 16)

        MaterialAlertDialogBuilder(this)
            .setTitle("Salva con nome")
            .setView(input)
            .setPositiveButton("Salva") { _, _ ->
                val name = input.text.toString().ifBlank { "DexPDF_${System.currentTimeMillis()}" }
                saveFileLauncher.launch("$name.pdf")
            }
            .setNeutralButton("Salvataggio rapido") { _, _ ->
                quickSave()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun quickSave() {
        val pdfFile = currentPdfFile ?: return
        try {
            if (pageAnnotations.isNotEmpty()) {
                val bitmap = cleanPageBitmap ?: return
                PdfModifier.applyOverlays(
                    pdfFile, currentPage, pageAnnotations,
                    bitmap.width.toFloat(), bitmap.height.toFloat()
                )
            }
            PdfModifier.saveToDownloads(this, pdfFile)
            pageAnnotations.clear()
            binding.overlayView.clearOverlays()
            openPdfFromFile(pdfFile)
            showToast("Salvato nei Download!")
        } catch (e: Exception) {
            showToast("Errore: ${e.message}")
        }
    }

    private fun saveToUri(uri: Uri) {
        val pdfFile = currentPdfFile ?: return
        try {
            if (pageAnnotations.isNotEmpty()) {
                val bitmap = cleanPageBitmap ?: return
                PdfModifier.applyOverlays(
                    pdfFile, currentPage, pageAnnotations,
                    bitmap.width.toFloat(), bitmap.height.toFloat()
                )
            }
            contentResolver.openOutputStream(uri)?.use { output ->
                pdfFile.inputStream().use { input -> input.copyTo(output) }
            }
            pageAnnotations.clear()
            binding.overlayView.clearOverlays()
            openPdfFromFile(pdfFile)
            showToast("File salvato!")
        } catch (e: Exception) {
            showToast("Errore: ${e.message}")
        }
    }

    // --- OCR ---

    private fun runOcr() {
        val bitmap = displayBitmap ?: return
        showToast("Riconoscimento testo...")

        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                if (result.text.isBlank()) {
                    showToast("Nessun testo trovato")
                    return@addOnSuccessListener
                }
                val editText = EditText(this)
                editText.setText(result.text)
                editText.setSelection(0)
                editText.setPadding(48, 32, 48, 16)
                editText.minLines = 5

                MaterialAlertDialogBuilder(this)
                    .setTitle("Testo riconosciuto")
                    .setView(editText)
                    .setPositiveButton("Copia") { _, _ ->
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OCR", editText.text))
                        showToast("Testo copiato!")
                    }
                    .setNeutralButton("Aggiungi al PDF") { _, _ ->
                        val text = editText.text.toString()
                        if (text.isNotBlank()) {
                            binding.overlayView.setPlacementMode(OverlayView.PlacementMode.TEXT)
                            showToast("Tocca dove vuoi posizionare il testo")
                        }
                    }
                    .setNegativeButton("Chiudi", null)
                    .show()
            }
            .addOnFailureListener { e ->
                showToast("Errore OCR: ${e.message}")
            }
    }

    // --- Dialogs ---

    private fun showHighlightColorPicker() {
        val colors = arrayOf("Giallo", "Verde", "Rosa", "Azzurro", "Arancione")
        val colorValues = intArrayOf(
            Color.YELLOW,
            Color.rgb(76, 175, 80),
            Color.rgb(233, 30, 99),
            Color.rgb(3, 169, 244),
            Color.rgb(255, 152, 0)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Colore evidenziatore")
            .setItems(colors) { _, which ->
                binding.overlayView.setHighlightColor(colorValues[which])
                binding.overlayView.setPlacementMode(OverlayView.PlacementMode.HIGHLIGHT)
                showToast("Disegna per evidenziare")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showSignatureDialog() {
        val signatureManager = SignatureManager(this)
        val signatures = signatureManager.getSavedSignatures()

        if (signatures.isEmpty()) {
            startActivity(Intent(this, SignatureManagerActivity::class.java))
            return
        }

        val dialogBinding = DialogSelectSignatureBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this).setView(dialogBinding.root).create()

        signatures.getOrNull(0)?.let { file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            dialogBinding.imgSig1Preview.setImageBitmap(bitmap)
            dialogBinding.imgSig1Preview.setOnClickListener {
                binding.overlayView.setPlacementMode(OverlayView.PlacementMode.SIGNATURE, signatureBitmap = bitmap)
                showToast("Tocca dove posizionare la firma")
                dialog.dismiss()
            }
        }

        signatures.getOrNull(1)?.let { file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            dialogBinding.imgSig2Preview.setImageBitmap(bitmap)
            dialogBinding.imgSig2Preview.setOnClickListener {
                binding.overlayView.setPlacementMode(OverlayView.PlacementMode.SIGNATURE, signatureBitmap = bitmap)
                showToast("Tocca dove posizionare la firma")
                dialog.dismiss()
            }
        }

        dialogBinding.btnManageSignatures.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SignatureManagerActivity::class.java))
        }
        dialog.show()
    }

    private fun convertWordToPdf(uri: Uri) {
        showToast("Conversione in corso...")
        Thread {
            try {
                val pdfFile = WordConverter.convert(this, uri)
                runOnUiThread { openPdfFromFile(pdfFile); showToast("Conversione completata!") }
            } catch (e: Exception) {
                runOnUiThread { showToast("Errore: ${e.message}") }
            }
        }.start()
    }

    // --- Cleanup ---

    private fun closePdfRenderer() {
        pdfRenderer?.close(); pdfRenderer = null
        fileDescriptor?.close(); fileDescriptor = null
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        closePdfRenderer()
    }
}

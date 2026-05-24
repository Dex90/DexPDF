package com.pdfeditor.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.github.chrisbanes.photoview.PhotoView
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
    private var currentPageBitmap: Bitmap? = null

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
            binding.textInputBar.visibility = View.VISIBLE
            binding.editTextDirect.requestFocus()
            binding.overlayView.setPlacementMode(OverlayView.PlacementMode.TEXT)
        }

        binding.btnCancelText.setOnClickListener { cancelTextMode() }

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
            if (currentPage > 0) { currentPage--; renderPage() }
        }

        binding.btnNextPage.setOnClickListener {
            if (currentPage < totalPages - 1) { currentPage++; renderPage() }
        }
    }

    private fun setupOverlay() {
        binding.overlayView.setOnPlacementListener(object : OverlayView.OnPlacementListener {
            override fun onTextPlaced(x: Float, y: Float, text: String, textSize: Float) {}

            override fun onSignaturePlaced(x: Float, y: Float, bitmap: Bitmap) {
                binding.overlayView.setPlacementMode(OverlayView.PlacementMode.NONE)
            }

            override fun onTextPositionSelected(x: Float, y: Float) {
                if (pendingCheckMark) {
                    binding.overlayView.addTextAt(x, y, "X", 18f)
                    pendingCheckMark = false
                    binding.overlayView.setPlacementMode(OverlayView.PlacementMode.NONE)
                } else {
                    val text = binding.editTextDirect.text.toString()
                    if (text.isNotBlank()) {
                        binding.overlayView.addTextAt(x, y, text, 14f)
                        binding.editTextDirect.text?.clear()
                    } else {
                        showToast("Scrivi prima il testo")
                    }
                }
            }
        })
    }

    private fun hasFile(): Boolean {
        if (currentPdfFile == null) {
            showToast("Apri prima un file")
            return false
        }
        return true
    }

    private fun cancelTextMode() {
        pendingCheckMark = false
        binding.textInputBar.visibility = View.GONE
        binding.editTextDirect.text?.clear()
        binding.overlayView.setPlacementMode(OverlayView.PlacementMode.NONE)
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
            currentPageBitmap = bitmap
            binding.pdfPageView.setImageBitmap(bitmap)
            binding.tvPageIndicator.text = getString(R.string.page_indicator, currentPage + 1, totalPages)
        }
    }

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
            val overlays = binding.overlayView.getOverlayItems()
            if (overlays.isNotEmpty()) {
                PdfModifier.applyOverlays(pdfFile, currentPage, overlays,
                    binding.pdfPageView.width.toFloat(), binding.pdfPageView.height.toFloat())
            }
            PdfModifier.saveToDownloads(this, pdfFile)
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
            val overlays = binding.overlayView.getOverlayItems()
            if (overlays.isNotEmpty()) {
                PdfModifier.applyOverlays(pdfFile, currentPage, overlays,
                    binding.pdfPageView.width.toFloat(), binding.pdfPageView.height.toFloat())
            }
            contentResolver.openOutputStream(uri)?.use { output ->
                pdfFile.inputStream().use { input -> input.copyTo(output) }
            }
            binding.overlayView.clearOverlays()
            openPdfFromFile(pdfFile)
            showToast("File salvato!")
        } catch (e: Exception) {
            showToast("Errore: ${e.message}")
        }
    }

    private fun runOcr() {
        val bitmap = currentPageBitmap ?: return
        showToast("Riconoscimento testo...")

        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                if (result.text.isBlank()) {
                    showToast("Nessun testo trovato")
                    return@addOnSuccessListener
                }
                // Show recognized text in a dialog for editing
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
                            binding.editTextDirect.setText(text)
                            binding.textInputBar.visibility = View.VISIBLE
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

    private fun showHighlightColorPicker() {
        val colors = arrayOf("Giallo", "Verde", "Rosa", "Azzurro", "Arancione")
        val colorValues = intArrayOf(
            android.graphics.Color.YELLOW,
            android.graphics.Color.rgb(76, 175, 80),
            android.graphics.Color.rgb(233, 30, 99),
            android.graphics.Color.rgb(3, 169, 244),
            android.graphics.Color.rgb(255, 152, 0)
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

    private fun closePdfRenderer() {
        pdfRenderer?.close(); pdfRenderer = null
        fileDescriptor?.close(); fileDescriptor = null
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { super.onDestroy(); closePdfRenderer() }
}

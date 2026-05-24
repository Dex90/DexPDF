package com.pdfeditor.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var isAddingText = false
    private var isAddingSignature = false
    private var pendingText: String = ""
    private var pendingTextSize: Float = 14f
    private var pendingSignatureBitmap: Bitmap? = null
    private var pendingCheckMark = false

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleFileOpen(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupButtons()
        setupOverlay()

        // Handle "Open With" intent
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
            if (currentPdfUri == null && currentPdfFile == null) {
                showToast(getString(R.string.no_file_open))
                return@setOnClickListener
            }
            // Show text input bar and enable placement mode
            isAddingText = true
            binding.textInputBar.visibility = View.VISIBLE
            binding.editTextDirect.requestFocus()
            binding.overlayView.setPlacementMode(OverlayView.PlacementMode.TEXT)
        }

        binding.btnCancelText.setOnClickListener {
            cancelTextMode()
        }

        binding.btnAddCheck.setOnClickListener {
            if (currentPdfUri == null && currentPdfFile == null) {
                showToast(getString(R.string.no_file_open))
                return@setOnClickListener
            }
            // Place an "X" mark directly - enable placement mode
            isAddingText = true
            binding.overlayView.setPlacementMode(OverlayView.PlacementMode.TEXT)
            pendingCheckMark = true
            showToast("Tocca dove vuoi mettere la X")
        }

        binding.btnSignature.setOnClickListener {
            if (currentPdfUri == null && currentPdfFile == null) {
                showToast(getString(R.string.no_file_open))
                return@setOnClickListener
            }
            showSignatureDialog()
        }

        binding.btnHighlight.setOnClickListener {
            if (currentPdfUri == null && currentPdfFile == null) {
                showToast(getString(R.string.no_file_open))
                return@setOnClickListener
            }
            showHighlightColorPicker()
        }

        binding.btnEraser.setOnClickListener {
            if (currentPdfUri == null && currentPdfFile == null) {
                showToast(getString(R.string.no_file_open))
                return@setOnClickListener
            }
            binding.overlayView.setPlacementMode(OverlayView.PlacementMode.ERASER)
            showToast("Disegna sopra il testo da cancellare")
        }

        binding.btnSave.setOnClickListener {
            savePdf()
        }

        binding.btnPrevPage.setOnClickListener {
            if (currentPage > 0) {
                currentPage--
                renderPage()
            }
        }

        binding.btnNextPage.setOnClickListener {
            if (currentPage < totalPages - 1) {
                currentPage++
                renderPage()
            }
        }
    }

    private fun setupOverlay() {
        binding.overlayView.setOnPlacementListener(object : OverlayView.OnPlacementListener {
            override fun onTextPlaced(x: Float, y: Float, text: String, textSize: Float) {
                isAddingText = false
            }

            override fun onSignaturePlaced(x: Float, y: Float, bitmap: Bitmap) {
                isAddingSignature = false
                binding.overlayView.setPlacementMode(OverlayView.PlacementMode.NONE)
            }

            override fun onTextPositionSelected(x: Float, y: Float) {
                if (pendingCheckMark) {
                    // Place X mark directly
                    binding.overlayView.addTextAt(x, y, "X", 18f)
                    pendingCheckMark = false
                    binding.overlayView.setPlacementMode(OverlayView.PlacementMode.NONE)
                    isAddingText = false
                } else {
                    // User tapped the PDF - place text directly from input bar
                    val text = binding.editTextDirect.text.toString()
                    if (text.isNotBlank()) {
                        binding.overlayView.addTextAt(x, y, text, 14f)
                        // Clear input but keep mode active for more text
                        binding.editTextDirect.text?.clear()
                    } else {
                        showToast("Scrivi prima il testo nella barra in basso")
                    }
                }
            }
        })
    }

    private fun cancelTextMode() {
        isAddingText = false
        pendingCheckMark = false
        binding.textInputBar.visibility = View.GONE
        binding.editTextDirect.text?.clear()
        binding.overlayView.setPlacementMode(OverlayView.PlacementMode.NONE)
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
            .setTitle("Scegli colore evidenziatore")
            .setItems(colors) { _, which ->
                binding.overlayView.setHighlightColor(colorValues[which])
                binding.overlayView.setPlacementMode(OverlayView.PlacementMode.HIGHLIGHT)
                showToast("Disegna con il dito per evidenziare")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun handleIncomingIntent(intent: Intent) {
        val uri = intent.data ?: return
        val mimeType = contentResolver.getType(uri) ?: return

        when {
            mimeType == "application/pdf" -> {
                openPdf(uri)
            }
            mimeType.contains("word") || mimeType.contains("document") -> {
                convertWordToPdf(uri)
            }
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

            // Copy to cache for rendering
            val cacheFile = File(cacheDir, "current.pdf")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
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
            showToast(getString(R.string.error_generic) + ": ${e.message}")
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
            showToast(getString(R.string.error_generic) + ": ${e.message}")
        }
    }

    private fun renderPage() {
        pdfRenderer?.let { renderer ->
            val page = renderer.openPage(currentPage)
            val bitmap = Bitmap.createBitmap(
                page.width * 2,
                page.height * 2,
                Bitmap.Config.ARGB_8888
            )
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            binding.pdfPageView.setImageBitmap(bitmap)
            binding.tvPageIndicator.text = getString(R.string.page_indicator, currentPage + 1, totalPages)
        }
    }

    private fun showSignatureDialog() {
        val signatureManager = SignatureManager(this)
        val signatures = signatureManager.getSavedSignatures()

        if (signatures.isEmpty()) {
            // Go directly to signature manager
            startActivity(Intent(this, SignatureManagerActivity::class.java))
            return
        }

        val dialogBinding = DialogSelectSignatureBinding.inflate(layoutInflater)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        // Load previews
        signatures.getOrNull(0)?.let { file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            dialogBinding.imgSig1Preview.setImageBitmap(bitmap)
            dialogBinding.imgSig1Preview.setOnClickListener {
                pendingSignatureBitmap = bitmap
                isAddingSignature = true
                binding.overlayView.setPlacementMode(
                    OverlayView.PlacementMode.SIGNATURE,
                    signatureBitmap = bitmap
                )
                showToast(getString(R.string.place_signature))
                dialog.dismiss()
            }
        }

        signatures.getOrNull(1)?.let { file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            dialogBinding.imgSig2Preview.setImageBitmap(bitmap)
            dialogBinding.imgSig2Preview.setOnClickListener {
                pendingSignatureBitmap = bitmap
                isAddingSignature = true
                binding.overlayView.setPlacementMode(
                    OverlayView.PlacementMode.SIGNATURE,
                    signatureBitmap = bitmap
                )
                showToast(getString(R.string.place_signature))
                dialog.dismiss()
            }
        }

        dialogBinding.btnManageSignatures.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SignatureManagerActivity::class.java))
        }

        dialog.show()
    }

    private fun savePdf() {
        val pdfFile = currentPdfFile ?: run {
            showToast(getString(R.string.no_file_open))
            return
        }

        try {
            val overlays = binding.overlayView.getOverlayItems()
            if (overlays.isEmpty()) {
                showToast(getString(R.string.file_saved))
                return
            }

            val pageWidth = binding.pdfPageView.width.toFloat()
            val pageHeight = binding.pdfPageView.height.toFloat()

            PdfModifier.applyOverlays(
                pdfFile,
                currentPage,
                overlays,
                pageWidth,
                pageHeight
            )

            // Refresh view
            openPdfFromFile(pdfFile)
            binding.overlayView.clearOverlays()
            showToast(getString(R.string.file_saved))

            // Also save to Downloads
            PdfModifier.saveToDownloads(this, pdfFile)
        } catch (e: Exception) {
            showToast(getString(R.string.error_generic) + ": ${e.message}")
        }
    }

    private fun convertWordToPdf(uri: Uri) {
        showToast(getString(R.string.converting))
        Thread {
            try {
                val pdfFile = WordConverter.convert(this, uri)
                runOnUiThread {
                    showToast(getString(R.string.conversion_complete))
                    openPdfFromFile(pdfFile)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showToast(getString(R.string.error_generic) + ": ${e.message}")
                }
            }
        }.start()
    }

    private fun closePdfRenderer() {
        pdfRenderer?.close()
        pdfRenderer = null
        fileDescriptor?.close()
        fileDescriptor = null
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        closePdfRenderer()
    }
}

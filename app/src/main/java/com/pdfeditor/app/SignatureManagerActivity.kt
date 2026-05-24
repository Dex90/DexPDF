package com.pdfeditor.app

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pdfeditor.app.databinding.ActivitySignatureManagerBinding

class SignatureManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignatureManagerBinding
    private lateinit var signatureManager: SignatureManager

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processSignatureImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignatureManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        signatureManager = SignatureManager(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnAddSignature.setOnClickListener {
            if (signatureManager.canAddMore()) {
                pickImageLauncher.launch("image/*")
            } else {
                Toast.makeText(this, getString(R.string.max_signatures), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDelete1.setOnClickListener {
            signatureManager.deleteSignature(0)
            loadSignatures()
        }

        binding.btnDelete2.setOnClickListener {
            signatureManager.deleteSignature(1)
            loadSignatures()
        }

        loadSignatures()
    }

    override fun onResume() {
        super.onResume()
        loadSignatures()
    }

    private fun loadSignatures() {
        val signatures = signatureManager.getSavedSignatures()

        // Signature 1
        if (signatures.isNotEmpty()) {
            val bmp = BitmapFactory.decodeFile(signatures[0].absolutePath)
            binding.imgSignature1.setImageBitmap(bmp)
            binding.txtEmpty1.visibility = View.GONE
            binding.btnDelete1.visibility = View.VISIBLE
        } else {
            binding.imgSignature1.setImageBitmap(null)
            binding.txtEmpty1.visibility = View.VISIBLE
            binding.btnDelete1.visibility = View.GONE
        }

        // Signature 2
        if (signatures.size > 1) {
            val bmp = BitmapFactory.decodeFile(signatures[1].absolutePath)
            binding.imgSignature2.setImageBitmap(bmp)
            binding.txtEmpty2.visibility = View.GONE
            binding.btnDelete2.visibility = View.VISIBLE
        } else {
            binding.imgSignature2.setImageBitmap(null)
            binding.txtEmpty2.visibility = View.VISIBLE
            binding.btnDelete2.visibility = View.GONE
        }

        // Update button state
        binding.btnAddSignature.isEnabled = signatureManager.canAddMore()
    }

    private fun processSignatureImage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (bitmap != null) {
                val saved = signatureManager.saveSignature(bitmap)
                if (saved != null) {
                    Toast.makeText(this, getString(R.string.signature_saved), Toast.LENGTH_SHORT).show()
                    loadSignatures()
                } else {
                    Toast.makeText(this, getString(R.string.max_signatures), Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
        }
    }
}

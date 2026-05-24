package com.pdfeditor.app

import android.app.Application

class DexPdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PdfModifier.initPdfBox(this)
    }
}

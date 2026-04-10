package com.resumestudio.reviewer.model.enums;

public enum ParseSource {
    PDF_TEXT,  // Clean PDF with extractable text
    PDF_OCR,   // Scanned PDF — text via Tesseract
    DOCX       // Word document via Apache POI
}

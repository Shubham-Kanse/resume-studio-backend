package com.resumestudio.reviewer.ingest;

import com.resumestudio.reviewer.ingest.ResumeIngestService.UnsupportedFileTypeException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeIngestServiceEdgeCaseTest {

    private final PdfExtractor pdfExtractor = mock(PdfExtractor.class);
    private final DocxExtractor docxExtractor = mock(DocxExtractor.class);
    private final OcrFallbackExtractor ocrExtractor = mock(OcrFallbackExtractor.class);
    private final DocumentCache cache = mock(DocumentCache.class);
    private final ResumeIngestService service =
        new ResumeIngestService(pdfExtractor, docxExtractor, ocrExtractor, cache);

    @Test void emptyFile_throws() {
        var file = new MockMultipartFile("resume", "cv.pdf", "application/pdf", new byte[0]);
        assertThrows(UnsupportedFileTypeException.class, () -> service.ingest(file));
    }

    @Test void fileTooLarge_throws() {
        byte[] big = new byte[51 * 1024 * 1024];
        var file = new MockMultipartFile("resume", "cv.pdf", "application/pdf", big);
        var ex = assertThrows(UnsupportedFileTypeException.class, () -> service.ingest(file));
        assertTrue(ex.getMessage().contains("too large"));
    }

    @Test void legacyDocFormat_throwsWithHelpfulMessage() {
        var file = new MockMultipartFile("resume", "cv.doc", "application/msword", new byte[]{1, 2, 3});
        var ex = assertThrows(UnsupportedFileTypeException.class, () -> service.ingest(file));
        assertTrue(ex.getMessage().contains(".docx"), "Should tell user to save as .docx");
    }

    @Test void unsupportedExtension_throws() {
        var file = new MockMultipartFile("resume", "cv.txt", "text/plain", new byte[]{1, 2, 3});
        assertThrows(UnsupportedFileTypeException.class, () -> service.ingest(file));
    }

    @Test void nullFilename_doesNotCrash() throws Exception {
        var raw = new RawDocument();
        raw.setFullText("some text");
        raw.setParseConfidence(0.9);
        raw.setScanned(false);
        raw.setPages(java.util.List.of());
        when(cache.get(any())).thenReturn(null);
        when(pdfExtractor.extract(any(), any())).thenReturn(raw);

        var file = new MockMultipartFile("resume", null, "application/pdf", new byte[]{1, 2, 3});
        assertDoesNotThrow(() -> service.ingest(file));
    }

    @Test void scannedPdf_triggersOcrFallback() throws Exception {
        var scannedRaw = new RawDocument();
        scannedRaw.setScanned(true);
        scannedRaw.setParseConfidence(0.1);
        scannedRaw.setFullText("");
        scannedRaw.setPages(java.util.List.of());

        var ocrRaw = new RawDocument();
        ocrRaw.setScanned(false);
        ocrRaw.setParseConfidence(0.8);
        ocrRaw.setFullText("OCR extracted text");
        ocrRaw.setPages(java.util.List.of());

        when(cache.get(any())).thenReturn(null);
        when(pdfExtractor.extract(any(), any())).thenReturn(scannedRaw);
        when(ocrExtractor.extract(any(), any())).thenReturn(ocrRaw);

        var file = new MockMultipartFile("resume", "cv.pdf", "application/pdf", new byte[]{1, 2, 3});
        RawDocument result = service.ingest(file);

        verify(ocrExtractor).extract(any(), any());
        assertEquals("OCR extracted text", result.getFullText());
    }
}

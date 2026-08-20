package com.jundaodsj.insightops.infrastructure.knowledge;

import com.jundaodsj.insightops.knowledge.application.DocumentCollectionException;
import com.jundaodsj.insightops.knowledge.application.KnowledgeFileStorage;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.knowledge.application.OfficialDocumentGateway;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class UploadedDocumentCollector {
    private final KnowledgeFileStorage storage;
    private final KnowledgeDocumentChunker chunker;
    private final KnowledgeUploadProperties properties;

    public UploadedDocumentCollector(KnowledgeFileStorage storage, KnowledgeDocumentChunker chunker,
                                     KnowledgeUploadProperties properties) {
        this.storage = storage;
        this.chunker = chunker;
        this.properties = properties;
    }

    public List<KnowledgeStore.DocumentPage> collect(
            KnowledgeStore.SourceTask source, OfficialDocumentGateway.CrawlOptions options,
            OfficialDocumentGateway.ProgressListener listener) {
        if (source.uploadStorageKey() == null || source.uploadOriginalName() == null) {
            throw failure("Upload metadata is missing");
        }
        try {
            byte[] bytes;
            try (var input = storage.open(source.uploadStorageKey())) {
                bytes = input.readNBytes((int) Math.min(Integer.MAX_VALUE, properties.getMaxFileBytes() + 1));
            }
            if (bytes.length > properties.getMaxFileBytes()) throw failure("Upload exceeds configured byte limit");
            String mediaType = source.uploadMediaType() == null ? "" : source.uploadMediaType();
            if (mediaType.equals("application/pdf")) return pdf(source, options, listener, bytes);
            return text(source, options, listener, bytes);
        } catch (DocumentCollectionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DocumentCollectionException(DocumentCollectionException.Code.TRANSIENT_REMOTE,
                    "Unable to read uploaded knowledge file", exception);
        }
    }

    private List<KnowledgeStore.DocumentPage> pdf(KnowledgeStore.SourceTask source,
                                                   OfficialDocumentGateway.CrawlOptions options,
                                                   OfficialDocumentGateway.ProgressListener listener,
                                                   byte[] bytes) {
        if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw failure("Uploaded PDF signature is invalid");
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw failure("Encrypted PDF files are not supported");
            int pages = document.getNumberOfPages();
            if (pages < 1 || pages > properties.getMaxPdfPages()) throw failure("PDF page count exceeds configured limit");
            List<KnowledgeStore.DocumentPage> result = new ArrayList<>();
            int totalCharacters = 0;
            for (int page = 1; page <= pages; page++) {
                listener.onProgress(new KnowledgeStore.CollectionProgress(
                        pages, pages, page - 1, result.size(), display(source, page, pages)));
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page); stripper.setEndPage(page);
                String extracted = stripper.getText(document).replace('\u0000', ' ').strip();
                totalCharacters += extracted.length();
                if (totalCharacters > properties.getMaxExtractedCharacters()) {
                    throw failure("Extracted PDF text exceeds configured limit");
                }
                String content = "# " + source.uploadOriginalName() + " · Page " + page + "\n\n" + extracted;
                var chunks = chunker.chunk(content, options.chunkMaxTokens(), options.chunkOverlapTokens());
                if (!chunks.isEmpty()) result.add(new KnowledgeStore.DocumentPage(
                        source.rootUrl() + "#page=" + page, source.uploadOriginalName(), "und",
                        "page-" + page, KnowledgeDocumentChunker.sha256(content), content,
                        source.fetchEtag(), source.fetchLastModified(), chunks));
                listener.onProgress(new KnowledgeStore.CollectionProgress(
                        pages, pages, page, result.size(), display(source, page, pages)));
            }
            if (result.isEmpty()) throw failure("PDF contains no extractable text");
            return List.copyOf(result);
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException exception) {
            throw failure("Encrypted PDF files are not supported");
        } catch (IOException exception) {
            throw new DocumentCollectionException(DocumentCollectionException.Code.UNSUPPORTED_CONTENT,
                    "Uploaded PDF could not be parsed", exception);
        }
    }

    private List<KnowledgeStore.DocumentPage> text(KnowledgeStore.SourceTask source,
                                                    OfficialDocumentGateway.CrawlOptions options,
                                                    OfficialDocumentGateway.ProgressListener listener,
                                                    byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8).replace('\u0000', ' ').strip();
        if (content.length() > properties.getMaxExtractedCharacters()) throw failure("Text exceeds configured limit");
        listener.onProgress(new KnowledgeStore.CollectionProgress(1, 1, 0, 0, source.uploadOriginalName()));
        var chunks = chunker.chunk(content, options.chunkMaxTokens(), options.chunkOverlapTokens());
        if (chunks.isEmpty()) throw failure("Uploaded file contains no collectable text");
        listener.onProgress(new KnowledgeStore.CollectionProgress(1, 1, 1, 1, source.uploadOriginalName()));
        return List.of(new KnowledgeStore.DocumentPage(source.rootUrl(), source.uploadOriginalName(), "und",
                null, KnowledgeDocumentChunker.sha256(content), content,
                source.fetchEtag(), source.fetchLastModified(), chunks));
    }

    private static String display(KnowledgeStore.SourceTask source, int page, int total) {
        return source.uploadOriginalName() + " · page " + page + "/" + total;
    }

    private static DocumentCollectionException failure(String message) {
        return new DocumentCollectionException(DocumentCollectionException.Code.UNSUPPORTED_CONTENT, message);
    }
}

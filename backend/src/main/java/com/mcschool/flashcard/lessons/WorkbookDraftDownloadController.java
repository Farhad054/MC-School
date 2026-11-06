package com.mcschool.flashcard.lessons;

import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/workbook-drafts")
public class WorkbookDraftDownloadController {
    private final WorkbookDraftDownloadService links;
    public WorkbookDraftDownloadController(WorkbookDraftDownloadService links) { this.links=links; }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam String token) {
        LessonPdfDraftService.PreviewFile file=links.download(token);
        String filename=LessonPdfUpload.filename(file.filename(),"Mindcrafti-draft.pdf");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename,StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer")
                .contentLength(file.bytes().length).body(file.bytes());
    }
}

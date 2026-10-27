package com.example.chat.controller;

import com.example.chat.service.Store;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final Store store;

    public FileController(Store store) {
        this.store = store;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestParam("file") MultipartFile file) {
        store.requireUser(auth);
        if (file.isEmpty()) throw Store.bad("The file is empty");

        Store.StoredFile f = store.saveFile(file);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("name", f.name());
        m.put("mime", f.mime());
        m.put("size", f.size());
        m.put("url", "/api/files/" + f.id());
        return m;
    }

    // File URLs are unguessable UUIDs so <img> and <video> tags can load them without headers.
    @GetMapping("/{id}")
    public ResponseEntity<Resource> get(@PathVariable String id,
                                        @RequestParam(value = "download", defaultValue = "false") boolean download) {
        Store.StoredFile f = store.file(id);
        if (f == null) return ResponseEntity.notFound().build();

        MediaType type;
        try {
            type = MediaType.parseMediaType(f.mime());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }

        // Force HTML, SVG and XML to download instead of rendering, to prevent script injection
        String mime = f.mime().toLowerCase();
        boolean risky = mime.contains("html") || mime.contains("svg") || mime.contains("xml");
        String disposition = (download || risky) ? "attachment" : "inline";

        ContentDisposition cd = ContentDisposition.builder(disposition)
                .filename(f.name(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(f.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(f.path()));
    }
}

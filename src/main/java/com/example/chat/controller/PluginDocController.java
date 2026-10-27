package com.example.chat.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Plugin API documentation (static/doc-api-1.html) at /plugin/view/doc-api.
 * Any other address under /plugin/view/ gets the same page with a 404 status, so the page
 * can show its own 404 section.
 */
@Controller
public class PluginDocController {

    @GetMapping("/plugin/view/doc-api")
    public ResponseEntity<Resource> docs() {
        return page(HttpStatus.OK);
    }

    @GetMapping("/plugin/view/**")
    public ResponseEntity<Resource> notFound() {
        return page(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<Resource> page(HttpStatus status) {
        Resource page = new ClassPathResource("static/doc-api-1.html");
        if (!page.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(page);
    }
}

package com.example.chat.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the chat page at http://localhost:8080/ directly from the classpath. */
@Controller
public class HomeController {

    @GetMapping("/")
    public ResponseEntity<Resource> home() {
        Resource page = new ClassPathResource("static/index.html");
        if (!page.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(page);
    }
}

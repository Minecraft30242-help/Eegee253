package com.example.chat.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

/**
 * Handles errors for every URL. Unknown pages return index.html with a 404 status, so the
 * 404 screen in the chat page is shown. API errors return JSON.
 */
@Controller
public class ErrorPageController implements ErrorController {

    @RequestMapping("/error")
    @ResponseBody
    public ResponseEntity<?> error(HttpServletRequest request) {
        Object rawStatus = request.getAttribute("jakarta.servlet.error.status_code");
        int status = rawStatus instanceof Integer code ? code : 500;
        String uri = String.valueOf(request.getAttribute("jakarta.servlet.error.request_uri"));

        if (uri.startsWith("/api/")) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", status, "error", "Request failed"));
        }

        if (status == 404) {
            Resource page = new ClassPathResource("static/index.html");
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_HTML)
                    .body(page);
        }

        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Error " + status);
    }
}

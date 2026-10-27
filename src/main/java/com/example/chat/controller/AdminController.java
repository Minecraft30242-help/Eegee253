package com.example.chat.controller;

import com.example.chat.service.CommandConsole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Receives server commands from ./server.sh cmd ... .
 * Only accepted from this machine and only with the token the server was started with.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CommandConsole console;
    private final String adminToken;

    public AdminController(CommandConsole console, @Value("${chat.admin-token:}") String adminToken) {
        this.console = console;
        this.adminToken = adminToken;
    }

    @PostMapping(value = "/command", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> command(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                          @RequestBody Map<String, String> body,
                                          HttpServletRequest request) {
        if (adminToken.isBlank()) {
            return deny("Admin commands are disabled. Start the server with ./server.sh");
        }
        if (!isLocal(request.getRemoteAddr())) {
            return deny("Admin commands are only accepted from this machine");
        }
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), adminToken.getBytes(StandardCharsets.UTF_8))) {
            return deny("Wrong admin token");
        }
        return ResponseEntity.ok(console.execute(body.get("command")));
    }

    private static ResponseEntity<String> deny(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.TEXT_PLAIN)
                .body(message);
    }

    private static boolean isLocal(String address) {
        return "127.0.0.1".equals(address) || "0:0:0:0:0:0:0:1".equals(address) || "::1".equals(address);
    }
}

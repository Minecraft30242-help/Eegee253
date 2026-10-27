package com.example.chat.controller;

import com.example.chat.service.Store;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final Store store;

    public AuthController(Store store) {
        this.store = store;
    }

    @PostMapping("/auth/signup")
    public Map<String, Object> signup(@RequestBody Map<String, String> body) {
        String token = store.signup(body.get("username"), body.get("password"), body.get("displayName"), body.get("email"));
        return Map.of("token", token, "user", store.profile(body.get("username")));
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String token = store.login(body.get("username"), body.get("password"));
        return Map.of("token", token, "user", store.profile(store.userForToken(token)));
    }

    @PostMapping("/auth/logout")
    public void logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        store.logout(auth == null ? null : auth.replace("Bearer ", ""));
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        return store.profile(store.requireUser(auth));
    }

    @PutMapping("/me")
    public Map<String, Object> updateMe(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @RequestBody Map<String, String> body) {
        return store.updateProfile(store.requireUser(auth), body.get("displayName"), body.get("bio"));
    }

    @GetMapping("/users/{username}")
    public Map<String, Object> userProfile(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @PathVariable String username) {
        store.requireUser(auth);
        if (!store.userExists(username))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No user named " + username);
        return store.profile(username);
    }

    @PutMapping("/me/avatar")
    public Map<String, Object> setAvatar(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @RequestBody Map<String, String> body) {
        return store.setAvatar(store.requireUser(auth), body.get("fileId"));
    }

    @DeleteMapping("/me/avatar")
    public Map<String, Object> removeAvatar(@RequestHeader(value = "Authorization", required = false) String auth) {
        return store.removeAvatar(store.requireUser(auth));
    }

    @DeleteMapping("/me")
    public void deleteMe(@RequestHeader(value = "Authorization", required = false) String auth) {
        store.deleteAccount(store.requireUser(auth));
    }
}

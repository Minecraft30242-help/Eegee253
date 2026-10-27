package com.example.chat.controller;

import com.example.chat.service.Store;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/contacts")
public class ContactController {

    private final Store store;

    public ContactController(Store store) {
        this.store = store;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        String me = store.requireUser(auth);
        return store.contactsOf(me).stream().map(other -> {
            Map<String, Object> p = store.profile(other);
            p.put("roomId", Store.dmRoom(me, other));
            return p;
        }).toList();
    }

    @PostMapping
    public void add(@RequestHeader(value = "Authorization", required = false) String auth,
                    @RequestBody Map<String, String> body) {
        store.addContact(store.requireUser(auth), body.get("username"));
    }

    @DeleteMapping("/{username}")
    public void remove(@RequestHeader(value = "Authorization", required = false) String auth,
                       @PathVariable String username) {
        store.removeContact(store.requireUser(auth), username);
    }
}

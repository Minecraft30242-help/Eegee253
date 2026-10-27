package com.example.chat.controller;

import com.example.chat.service.Store;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Ban management for admins, used by the ban panel in index.html. */
@RestController
@RequestMapping("/api/bans")
public class BanController {

    private final Store store;

    public BanController(Store store) {
        this.store = store;
    }

    @GetMapping
    public List<String> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        store.requireAdmin(auth);
        return store.bans();
    }

    @PostMapping
    public void ban(@RequestHeader(value = "Authorization", required = false) String auth,
                    @RequestBody Map<String, String> body) {
        String me = store.requireAdmin(auth);
        String target = body.get("username");
        if (me.equals(target)) throw Store.bad("You can't ban yourself");
        store.ban(target);
    }

    @DeleteMapping("/{username}")
    public void unban(@RequestHeader(value = "Authorization", required = false) String auth,
                      @PathVariable String username) {
        store.requireAdmin(auth);
        store.unban(username);
    }
}

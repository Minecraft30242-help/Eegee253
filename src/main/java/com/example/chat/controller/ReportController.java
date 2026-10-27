package com.example.chat.controller;

import com.example.chat.service.Store;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** User reports that ask admins to ban someone. Admins review them in the ban panel. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final Store store;

    public ReportController(Store store) {
        this.store = store;
    }

    @PostMapping
    public Store.Report report(@RequestHeader(value = "Authorization", required = false) String auth,
                               @RequestBody Map<String, String> body) {
        String me = store.requireUser(auth);
        return store.report(me, body.get("username"), body.get("reason"));
    }

    @GetMapping
    public List<Store.Report> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        store.requireAdmin(auth);
        return store.reports();
    }

    @DeleteMapping("/{id}")
    public void dismiss(@RequestHeader(value = "Authorization", required = false) String auth,
                        @PathVariable String id) {
        store.requireAdmin(auth);
        store.dismissReport(id);
    }
}

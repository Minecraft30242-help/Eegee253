package com.example.chat.controller;

import com.example.chat.service.Store;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plugin API. A plugin authenticates with its key in the X-Plugin-Key header.
 * Full documentation: /plugin/view/doc-api
 */
@RestController
@RequestMapping("/api/plugin")
public class PluginApiController {

    private final Store store;
    private final SimpMessagingTemplate template;

    public PluginApiController(Store store, SimpMessagingTemplate template) {
        this.store = store;
        this.template = template;
    }

    @GetMapping("/info")
    public Map<String, Object> info(@RequestHeader(value = "X-Plugin-Key", required = false) String key) {
        Store.Plugin plugin = requirePlugin(key);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", plugin.name());
        m.put("groupId", plugin.groupId());
        m.put("group", store.group(plugin.groupId()).name());
        return m;
    }

    @PostMapping("/messages")
    public Store.Message post(@RequestHeader(value = "X-Plugin-Key", required = false) String key,
                              @RequestBody Map<String, String> body) {
        Store.Plugin plugin = requirePlugin(key);
        Store.Message message = store.postFromPlugin(plugin, body.get("text"));
        template.convertAndSend("/topic/room/" + message.roomId(), message);
        return message;
    }

    private Store.Plugin requirePlugin(String key) {
        Store.Plugin plugin = store.pluginForKey(key);
        if (plugin == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid X-Plugin-Key header");
        return plugin;
    }
}

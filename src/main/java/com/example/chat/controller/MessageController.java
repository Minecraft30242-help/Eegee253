package com.example.chat.controller;

import com.example.chat.service.Store;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final Store store;
    private final SimpMessagingTemplate template;

    public MessageController(Store store, SimpMessagingTemplate template) {
        this.store = store;
        this.template = template;
    }

    @GetMapping
    public List<Store.Message> history(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestParam("room") String room) {
        String me = store.requireUser(auth);
        if (!store.canAccess(me, room))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have access to this chat");
        return store.messagesOf(room);
    }

    /** Marks a message as deleted and tells everyone in the chat. */
    @DeleteMapping("/{id}")
    public Store.Message delete(@RequestHeader(value = "Authorization", required = false) String auth,
                                @PathVariable String id) {
        String me = store.requireUser(auth);
        Store.Message updated = store.deleteMessage(me, id);
        template.convertAndSend("/topic/room/" + updated.roomId(), updated);
        return updated;
    }
}

package com.example.chat.controller;

import com.example.chat.service.Store;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class ChatSocketController {

    public record SendRequest(String token, String roomId, String type, String content, String fileId,
                              String clientId, String replyTo) {}

    private final Store store;
    private final SimpMessagingTemplate template;

    public ChatSocketController(Store store, SimpMessagingTemplate template) {
        this.store = store;
        this.template = template;
    }

    @MessageMapping("/chat.send")
    public void send(SendRequest req) {
        String me = store.userForToken(req.token());
        if (me == null || req.roomId() == null || !store.canAccess(me, req.roomId())) return;
        if (!store.canPost(me, req.roomId())) return; // only admins can post in this group

        Store.Message m = store.addMessage(req.roomId(), me, req.type(), req.content(), req.fileId(), req.clientId(), req.replyTo());
        template.convertAndSend("/topic/room/" + req.roomId(), m);
    }
}

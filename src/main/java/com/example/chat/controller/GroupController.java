package com.example.chat.controller;

import com.example.chat.service.Store;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final Store store;
    private final SimpMessagingTemplate template;

    public GroupController(Store store, SimpMessagingTemplate template) {
        this.store = store;
        this.template = template;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        String me = store.requireUser(auth);
        return store.groupsOf(me).stream().map(this::view).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody Map<String, String> body) {
        String me = store.requireUser(auth);
        return view(store.createGroup(me, body.get("name")));
    }

    @GetMapping("/{id}/link")
    public Map<String, String> link(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable String id) {
        String me = store.requireUser(auth);
        Store.Group g = store.group(id);
        store.requireInviter(me, id);
        return Map.of("code", g.inviteCode());
    }

    @PostMapping("/join/{code}")
    public Map<String, Object> join(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable String code) {
        return view(store.joinByCode(store.requireUser(auth), code));
    }

    @PostMapping("/{id}/leave")
    public void leave(@RequestHeader(value = "Authorization", required = false) String auth,
                      @PathVariable String id) {
        store.leaveGroup(store.requireUser(auth), id);
    }

    /** Deletes the group, its messages and files, and removes every member. Each member is told on their own channel. */
    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String auth,
                       @PathVariable String id) {
        String me = store.requireUser(auth);
        String roomId = Store.groupRoom(id);
        List<String> members = List.copyOf(store.group(id).members());
        store.deleteGroup(me, id);

        Store.Message notice = new Store.Message(UUID.randomUUID().toString(), roomId, me, "GROUP_DELETED",
                "", null, null, null, System.currentTimeMillis(), null, null, null, null, false);
        for (String member : members) {
            template.convertAndSend("/topic/user/" + member, notice);
        }
    }

    @GetMapping("/{id}/details")
    public Map<String, Object> details(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @PathVariable String id) {
        return store.groupDetails(store.requireUser(auth), id);
    }

    @PutMapping("/{id}/permissions")
    public Map<String, Object> permissions(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @PathVariable String id,
                                           @RequestBody Map<String, Object> body) {
        String me = store.requireUser(auth);
        store.setGroupPermissions(me, id,
                Boolean.TRUE.equals(body.get("membersCanInvite")),
                Boolean.TRUE.equals(body.get("adminsOnlyPost")));
        return store.groupDetails(me, id);
    }

    @PostMapping("/{id}/admins")
    public Map<String, Object> makeAdmin(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @PathVariable String id,
                                         @RequestBody Map<String, String> body) {
        String me = store.requireUser(auth);
        store.setGroupAdmin(me, id, body.get("username"), true);
        return store.groupDetails(me, id);
    }

    @DeleteMapping("/{id}/admins/{username}")
    public Map<String, Object> removeAdmin(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @PathVariable String id,
                                           @PathVariable String username) {
        String me = store.requireUser(auth);
        store.setGroupAdmin(me, id, username, false);
        return store.groupDetails(me, id);
    }

    @DeleteMapping("/{id}/members/{username}")
    public Map<String, Object> removeMember(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @PathVariable String id,
                                            @PathVariable String username) {
        String me = store.requireUser(auth);
        store.removeGroupMember(me, id, username);
        return store.groupDetails(me, id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> rename(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @PathVariable String id,
                                      @RequestBody Map<String, String> body) {
        String me = store.requireUser(auth);
        store.renameGroup(me, id, body.get("name"));
        return store.groupDetails(me, id);
    }

    @PutMapping("/{id}/photo")
    public Map<String, Object> setPhoto(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @PathVariable String id,
                                        @RequestBody Map<String, String> body) {
        String me = store.requireUser(auth);
        store.setGroupPhoto(me, id, body.get("fileId"));
        return store.groupDetails(me, id);
    }

    @DeleteMapping("/{id}/photo")
    public Map<String, Object> removePhoto(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @PathVariable String id) {
        String me = store.requireUser(auth);
        store.removeGroupPhoto(me, id);
        return store.groupDetails(me, id);
    }

    @GetMapping("/{id}/plugins")
    public List<Map<String, Object>> plugins(@RequestHeader(value = "Authorization", required = false) String auth,
                                             @PathVariable String id) {
        return store.pluginsOf(store.requireUser(auth), id);
    }

    @PostMapping("/{id}/plugins")
    public Map<String, Object> createPlugin(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @PathVariable String id,
                                            @RequestBody Map<String, String> body) {
        return store.createPlugin(store.requireUser(auth), id, body.get("name"));
    }

    @DeleteMapping("/{id}/plugins/{pluginId}")
    public void deletePlugin(@RequestHeader(value = "Authorization", required = false) String auth,
                             @PathVariable String id,
                             @PathVariable String pluginId) {
        store.deletePlugin(store.requireUser(auth), id, pluginId);
    }

    private Map<String, Object> view(Store.Group g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id());
        m.put("name", g.name());
        m.put("roomId", Store.groupRoom(g.id()));
        m.put("memberCount", g.members().size());
        m.put("owner", g.owner());
        return m;
    }
}

package com.example.chat.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * In-memory storage for users, sessions, contacts, groups, messages and files.
 * Everything is lost when the application restarts. Replace with a database for real use.
 */
@Service
public class Store {

    public record User(String username, String displayName, String bio, String passwordHash) {}
    public record Group(String id, String name, String owner, String inviteCode, Set<String> members) {}
    public record Message(String id, String roomId, String sender, String type, String content,
                          String fileId, String fileName, String mime, long timestamp, String clientId,
                          String replyTo, String replySender, String replyText, boolean deleted) {}
    public record StoredFile(String id, String name, String mime, long size, Path path) {}
    public record Report(String id, String reporter, String target, String reason, long timestamp) {}
    public record GroupPerms(boolean membersCanInvite, boolean adminsOnlyPost) {}
    public record Plugin(String id, String groupId, String name, String keyHash, long created) {}

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,20}");
    private static final Pattern GMAIL = Pattern.compile("[a-z0-9._%+-]+@gmail\\.com");
    private static final Set<String> TYPES = Set.of("TEXT", "IMAGE", "VIDEO", "GIF", "FILE");

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> contacts = new ConcurrentHashMap<>();
    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    private final Map<String, List<Message>> messages = new ConcurrentHashMap<>();
    private final Map<String, StoredFile> files = new ConcurrentHashMap<>();
    private final Set<String> bans = ConcurrentHashMap.newKeySet();
    private final Set<String> admins = ConcurrentHashMap.newKeySet();
    private final Map<String, Report> reports = new ConcurrentHashMap<>();
    private final Map<String, String> avatars = new ConcurrentHashMap<>(); // username -> file id
    private final Map<String, String> emailToUser = new ConcurrentHashMap<>(); // Gmail address -> username
    private final Map<String, Set<String>> groupAdmins = new ConcurrentHashMap<>(); // group id -> admin usernames
    private final Map<String, GroupPerms> groupPerms = new ConcurrentHashMap<>();   // group id -> permissions
    private final Map<String, String> groupPhotos = new ConcurrentHashMap<>();      // group id -> file id
    private final Map<String, Plugin> plugins = new ConcurrentHashMap<>();          // plugin id -> plugin
    private final Path uploadDir = Paths.get("uploads");

    public Store() {
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- helpers ----------

    public static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String dmRoom(String a, String b) {
        return a.compareTo(b) <= 0 ? "dm-" + a + "-" + b : "dm-" + b + "-" + a;
    }

    public static String groupRoom(String groupId) {
        return "group-" + groupId;
    }

    // ---------- accounts and sessions ----------

    public synchronized String signup(String username, String password, String displayName, String email) {
        if (username == null || !USERNAME.matcher(username).matches())
            throw bad("Username must be 3-20 letters, digits or _");
        String mail = email == null ? "" : email.trim().toLowerCase();
        if (!GMAIL.matcher(mail).matches())
            throw bad("Enter a valid Gmail address, for example name@gmail.com");
        if (password == null || password.length() < 6)
            throw bad("Password must be at least 6 characters");
        if (bans.contains(username))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This username is banned");
        if (users.containsKey(username))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is taken");
        if (emailToUser.containsKey(mail))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This Gmail address is already registered");

        String name = (displayName == null || displayName.isBlank()) ? username : displayName.trim();
        if (name.length() > 40) throw bad("Display name is too long");

        users.put(username, new User(username, name, "", sha256(password)));
        emailToUser.put(mail, username);
        contacts.put(username, ConcurrentHashMap.newKeySet());
        return newToken(username);
    }

    /** Logs in with a username or a Gmail address. */
    public synchronized String login(String identifier, String password) {
        String username = resolveUsername(identifier);
        if (username != null && bans.contains(username))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been banned");
        User u = username == null ? null : users.get(username);
        if (u == null || password == null || !u.passwordHash().equals(sha256(password)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong username or Gmail address, or password");
        return newToken(username);
    }

    private String resolveUsername(String identifier) {
        if (identifier == null) return null;
        String id = identifier.trim();
        if (id.contains("@")) return emailToUser.get(id.toLowerCase());
        return id;
    }

    private String newToken(String username) {
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, username);
        return token;
    }

    public void logout(String token) {
        if (token != null) tokens.remove(token);
    }

    public String userForToken(String token) {
        return token == null ? null : tokens.get(token);
    }

    public String requireUser(String authHeader) {
        String token = (authHeader != null && authHeader.startsWith("Bearer ")) ? authHeader.substring(7) : null;
        String username = userForToken(token);
        if (username == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        return username;
    }

    public boolean userExists(String username) {
        return username != null && users.containsKey(username);
    }

    public Map<String, Object> profile(String username) {
        User u = users.get(username);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.username());
        m.put("displayName", u.displayName());
        m.put("bio", u.bio());
        m.put("admin", admins.contains(username));
        m.put("avatarUrl", avatars.containsKey(username) ? "/api/files/" + avatars.get(username) : null);
        return m;
    }

    public synchronized Map<String, Object> updateProfile(String username, String displayName, String bio) {
        User old = users.get(username);
        String name = (displayName == null || displayName.isBlank()) ? old.username() : displayName.trim();
        if (name.length() > 40) throw bad("Display name is too long");
        String b = bio == null ? "" : bio.trim();
        if (b.length() > 300) throw bad("Bio is too long");
        users.put(username, new User(username, name, b, old.passwordHash()));
        return profile(username);
    }

    public synchronized void deleteAccount(String username) {
        users.remove(username);
        contacts.remove(username);
        admins.remove(username);
        avatars.remove(username);
        emailToUser.values().removeIf(username::equals);
        groupAdmins.values().forEach(set -> set.remove(username));
        reports.values().removeIf(r -> r.target().equals(username) || r.reporter().equals(username));
        contacts.values().forEach(s -> s.remove(username));
        tokens.values().removeIf(username::equals);
        groups.values().forEach(g -> g.members().remove(username));
        groups.values().removeIf(g -> g.members().isEmpty());
    }

    // ---------- profile picture ----------

    public synchronized Map<String, Object> setAvatar(String username, String fileId) {
        if (fileId == null) throw bad("File id is required");
        StoredFile f = files.get(fileId);
        if (f == null) throw notFound("File not found");
        String mime = f.mime().toLowerCase();
        if (!mime.startsWith("image/") || mime.contains("svg")) throw bad("Profile picture must be a PNG, JPEG, GIF or WebP image");
        avatars.put(username, fileId);
        return profile(username);
    }

    public synchronized Map<String, Object> removeAvatar(String username) {
        avatars.remove(username);
        return profile(username);
    }

    // ---------- contacts ----------

    public List<String> contactsOf(String username) {
        return new ArrayList<>(contacts.getOrDefault(username, Set.of()));
    }

    public synchronized void addContact(String username, String other) {
        if (other == null || other.isBlank()) throw bad("Username is required");
        if (username.equals(other)) throw bad("You can't add yourself");
        if (!users.containsKey(other)) throw notFound("No user named " + other);
        contacts.computeIfAbsent(username, k -> ConcurrentHashMap.newKeySet()).add(other);
    }

    public void removeContact(String username, String other) {
        Set<String> set = contacts.get(username);
        if (set != null) set.remove(other);
    }

    // ---------- groups ----------

    public synchronized Group createGroup(String owner, String name) {
        if (name == null || name.isBlank()) throw bad("Group name is required");
        Set<String> members = ConcurrentHashMap.newKeySet();
        members.add(owner);
        Group g = new Group(UUID.randomUUID().toString(), name.trim(), owner, newInviteCode(), members);
        groups.put(g.id(), g);
        groupAdmins.put(g.id(), ConcurrentHashMap.newKeySet());
        groupPerms.put(g.id(), new GroupPerms(true, false));
        return g;
    }

    private String newInviteCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public List<Group> groupsOf(String username) {
        return groups.values().stream()
                .filter(g -> g.members().contains(username))
                .collect(Collectors.toList());
    }

    public Group group(String id) {
        Group g = groups.get(id);
        if (g == null) throw notFound("Group not found");
        return g;
    }

    public synchronized Group joinByCode(String username, String code) {
        Group g = groups.values().stream()
                .filter(x -> x.inviteCode().equals(code))
                .findFirst()
                .orElseThrow(() -> notFound("This invite link is not valid"));
        g.members().add(username);
        return g;
    }

    /** Removes the user from a group. The group is removed when its last member leaves. */
    public synchronized void leaveGroup(String username, String groupId) {
        Group g = group(groupId);
        if (!g.members().contains(username)) throw bad("You are not in this group");
        g.members().remove(username);
        Set<String> managers = groupAdmins.get(groupId);
        if (managers != null) managers.remove(username);
        if (g.members().isEmpty()) removeGroupData(groupId);
    }

    /** Deletes a group, its messages and files, and removes every member. Only the owner or a site admin may do this. */
    public synchronized void deleteGroup(String username, String groupId) {
        Group g = group(groupId);
        if (!g.owner().equals(username) && !admins.contains(username))
            throw forbidden("Only the group owner or an admin can delete this group");

        // Remove every message in the group, including the uploaded files they point to
        for (Message m : messagesOf(groupRoom(groupId))) {
            if (m.fileId() == null) continue;
            StoredFile f = files.remove(m.fileId());
            if (f != null) {
                try {
                    Files.deleteIfExists(f.path());
                } catch (IOException ignored) {
                    // The record is already gone; a leftover file is harmless
                }
            }
        }
        g.members().clear(); // every member leaves
        removeGroupData(groupId);
    }

    private void removeGroupData(String groupId) {
        groups.remove(groupId);
        messages.remove(groupRoom(groupId));
        groupAdmins.remove(groupId);
        groupPerms.remove(groupId);
        groupPhotos.remove(groupId);
        plugins.values().removeIf(p -> p.groupId().equals(groupId));
    }

    // ---------- group permissions, administration and members ----------

    public boolean isGroupManager(String username, Group g) {
        return g.owner().equals(username) || groupAdmins.getOrDefault(g.id(), Set.of()).contains(username);
    }

    private GroupPerms perms(String groupId) {
        return groupPerms.getOrDefault(groupId, new GroupPerms(true, false));
    }

    private Group requireGroupMember(String username, String groupId) {
        Group g = group(groupId);
        if (!g.members().contains(username)) throw forbidden("You are not a member of this group");
        return g;
    }

    private Group requireManager(String username, String groupId) {
        Group g = requireGroupMember(username, groupId);
        if (!isGroupManager(username, g)) throw forbidden("Only group admins can do this");
        return g;
    }

    /** Throws unless the user may see the invite link: group admins always, members when allowed. */
    public void requireInviter(String username, String groupId) {
        Group g = requireGroupMember(username, groupId);
        if (!isGroupManager(username, g) && !perms(groupId).membersCanInvite())
            throw forbidden("Only group admins can share the invite link");
    }

    /** Whether the user may send a message to this room. */
    public boolean canPost(String username, String roomId) {
        if (!roomId.startsWith("group-")) return true;
        Group g = groups.get(roomId.substring("group-".length()));
        if (g == null || !g.members().contains(username)) return false;
        return !perms(g.id()).adminsOnlyPost() || isGroupManager(username, g);
    }

    public synchronized void setGroupPermissions(String username, String groupId, boolean membersCanInvite, boolean adminsOnlyPost) {
        requireManager(username, groupId);
        groupPerms.put(groupId, new GroupPerms(membersCanInvite, adminsOnlyPost));
    }

    public synchronized void setGroupAdmin(String username, String groupId, String target, boolean on) {
        Group g = requireGroupMember(username, groupId);
        if (!g.owner().equals(username)) throw forbidden("Only the group owner can change admins");
        if (target == null || !g.members().contains(target))
            throw bad((target == null ? "That user" : target) + " is not a member of this group");
        if (target.equals(g.owner())) throw bad("The owner is always an admin");
        Set<String> managers = groupAdmins.computeIfAbsent(groupId, k -> ConcurrentHashMap.newKeySet());
        if (on) managers.add(target);
        else managers.remove(target);
    }

    public synchronized void removeGroupMember(String username, String groupId, String target) {
        Group g = requireManager(username, groupId);
        if (target == null || !g.members().contains(target))
            throw bad((target == null ? "That user" : target) + " is not a member of this group");
        if (target.equals(username)) throw bad("Use Leave group to leave the group");
        if (target.equals(g.owner())) throw forbidden("The owner cannot be removed");
        boolean targetIsAdmin = groupAdmins.getOrDefault(groupId, Set.of()).contains(target);
        if (targetIsAdmin && !g.owner().equals(username)) throw forbidden("Only the owner can remove an admin");
        g.members().remove(target);
        Set<String> managers = groupAdmins.get(groupId);
        if (managers != null) managers.remove(target);
    }

    public Map<String, Object> groupDetails(String username, String groupId) {
        Group g = requireGroupMember(username, groupId);
        GroupPerms p = perms(groupId);
        Set<String> managers = groupAdmins.getOrDefault(groupId, Set.of());

        List<Map<String, Object>> members = g.members().stream().sorted().map(u -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("username", u);
            row.put("displayName", profile(u).get("displayName"));
            row.put("role", u.equals(g.owner()) ? "owner" : managers.contains(u) ? "admin" : "member");
            return row;
        }).collect(Collectors.toList());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id());
        m.put("name", g.name());
        m.put("owner", g.owner());
        m.put("myRole", g.owner().equals(username) ? "owner" : isGroupManager(username, g) ? "admin" : "member");
        m.put("membersCanInvite", p.membersCanInvite());
        m.put("adminsOnlyPost", p.adminsOnlyPost());
        m.put("photoUrl", groupPhotos.containsKey(groupId) ? "/api/files/" + groupPhotos.get(groupId) : null);
        m.put("members", members);
        return m;
    }

    public synchronized Group renameGroup(String username, String groupId, String name) {
        Group g = requireManager(username, groupId);
        if (name == null || name.isBlank()) throw bad("Group name is required");
        String clean = name.trim();
        if (clean.length() > 40) throw bad("Group name is too long (40 characters at most)");
        Group renamed = new Group(g.id(), clean, g.owner(), g.inviteCode(), g.members());
        groups.put(groupId, renamed);
        return renamed;
    }

    public synchronized void setGroupPhoto(String username, String groupId, String fileId) {
        requireManager(username, groupId);
        if (fileId == null) throw bad("File id is required");
        StoredFile f = files.get(fileId);
        if (f == null) throw notFound("File not found");
        String mime = f.mime().toLowerCase();
        if (!mime.startsWith("image/") || mime.contains("svg"))
            throw bad("Group photo must be a PNG, JPEG, GIF or WebP image");
        groupPhotos.put(groupId, fileId);
    }

    public synchronized void removeGroupPhoto(String username, String groupId) {
        requireManager(username, groupId);
        groupPhotos.remove(groupId);
    }

    // ---------- plugins ----------

    private static String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        new SecureRandom().nextBytes(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Creates a plugin. The returned key is shown only once; only its hash is stored. */
    public synchronized Map<String, Object> createPlugin(String username, String groupId, String name) {
        requireManager(username, groupId);
        String clean = name == null ? "" : name.trim();
        if (!clean.matches("[A-Za-z0-9 _-]{2,30}"))
            throw bad("Plugin name must be 2-30 letters, digits, spaces, _ or -");

        String key = "sc_" + randomHex(24);
        Plugin p = new Plugin(UUID.randomUUID().toString(), groupId, clean, sha256(key), System.currentTimeMillis());
        plugins.put(p.id(), p);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.id());
        out.put("name", p.name());
        out.put("key", key);
        return out;
    }

    public List<Map<String, Object>> pluginsOf(String username, String groupId) {
        requireManager(username, groupId);
        return plugins.values().stream()
                .filter(p -> p.groupId().equals(groupId))
                .sorted(Comparator.comparingLong(Plugin::created))
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.id());
                    m.put("name", p.name());
                    m.put("created", p.created());
                    return m;
                })
                .collect(Collectors.toList());
    }

    public synchronized void deletePlugin(String username, String groupId, String pluginId) {
        requireManager(username, groupId);
        Plugin p = plugins.get(pluginId);
        if (p == null || !p.groupId().equals(groupId)) throw notFound("Plugin not found");
        plugins.remove(pluginId);
    }

    /** Returns the plugin that owns this API key, or null. */
    public Plugin pluginForKey(String key) {
        if (key == null || key.isBlank()) return null;
        String hash = sha256(key);
        return plugins.values().stream().filter(p -> p.keyHash().equals(hash)).findFirst().orElse(null);
    }

    public Message postFromPlugin(Plugin p, String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) throw bad("Message text is required");
        if (clean.length() > 2000) throw bad("Message text is too long (2000 characters at most)");
        // Shown as "(plugin) name", which a username cannot contain, so it cannot be mistaken for a person
        return addMessage(groupRoom(p.groupId()), "(plugin) " + p.name(), "TEXT", clean, null, null, null);
    }

    private static ResponseStatusException forbidden(String msg) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
    }

    // ---------- access control and messages ----------

    public boolean canAccess(String username, String roomId) {
        if (roomId.startsWith("dm-")) {
            String[] parts = roomId.split("-");
            return parts.length == 3 && (parts[1].equals(username) || parts[2].equals(username));
        }
        if (roomId.startsWith("group-")) {
            Group g = groups.get(roomId.substring("group-".length()));
            return g != null && g.members().contains(username);
        }
        return false;
    }

    public Message addMessage(String roomId, String sender, String type, String content,
                              String fileId, String clientId, String replyTo) {
        if (!TYPES.contains(type)) throw bad("Unknown message type");

        String fileName = null;
        String mime = null;
        if (fileId != null && !fileId.isBlank()) {
            StoredFile f = files.get(fileId);
            if (f == null) throw notFound("File not found");
            fileName = f.name();
            mime = f.mime();
        } else {
            fileId = null;
        }

        // Keep a snapshot of the quoted message so the reply still shows it later
        String parentId = null;
        String replySender = null;
        String replyText = null;
        if (replyTo != null && !replyTo.isBlank()) {
            Message parent = messagesOf(roomId).stream()
                    .filter(x -> x.id().equals(replyTo))
                    .findFirst()
                    .orElse(null);
            if (parent != null) {
                parentId = parent.id();
                replySender = parent.sender();
                replyText = previewOf(parent);
            }
        }

        Message m = new Message(UUID.randomUUID().toString(), roomId, sender, type,
                content == null ? "" : content, fileId, fileName, mime, System.currentTimeMillis(),
                clientId, parentId, replySender, replyText, false);
        messages.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(m);
        return m;
    }

    private static String previewOf(Message m) {
        if (m.deleted()) return "Message deleted";
        if ("TEXT".equals(m.type())) {
            String text = m.content() == null ? "" : m.content();
            return text.length() > 80 ? text.substring(0, 80) + "..." : text;
        }
        String label = "[" + m.type().toLowerCase() + "]";
        return m.fileName() != null ? label + " " + m.fileName() : label;
    }

    /** Marks a message as deleted. Only its sender or an admin may do this. The message stays in place. */
    public synchronized Message deleteMessage(String username, String messageId) {
        for (List<Message> list : messages.values()) {
            for (int i = 0; i < list.size(); i++) {
                Message m = list.get(i);
                if (!m.id().equals(messageId)) continue;
                if (!m.sender().equals(username) && !admins.contains(username))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only delete your own messages");
                if (m.deleted()) return m;
                Message gone = new Message(m.id(), m.roomId(), m.sender(), m.type(), "", null, null, null,
                        m.timestamp(), m.clientId(), m.replyTo(), m.replySender(), m.replyText(), true);
                list.set(i, gone);
                return gone;
            }
        }
        throw notFound("Message not found");
    }

    public List<Message> messagesOf(String roomId) {
        return messages.getOrDefault(roomId, List.of());
    }

    // ---------- bans and admins (used by the command console) ----------

    public synchronized void ban(String name) {
        if (name == null || !USERNAME.matcher(name).matches())
            throw bad("Username must be 3-20 letters, digits or _");
        bans.add(name);
        tokens.values().removeIf(name::equals); // end any active sessions
        reports.values().removeIf(r -> r.target().equals(name)); // reports are resolved by the ban
    }

    public synchronized void unban(String name) {
        if (!bans.remove(name)) throw bad(name + " is not banned");
    }

    public synchronized void addAdmin(String name) {
        if (!users.containsKey(name)) throw notFound("No user named " + name);
        admins.add(name);
    }

    public synchronized void removeAdmin(String name) {
        if (!admins.remove(name)) throw bad(name + " is not an admin");
    }

    public List<String> admins() {
        return new ArrayList<>(new TreeSet<>(admins));
    }

    public List<String> bans() {
        return new ArrayList<>(new TreeSet<>(bans));
    }

    /** Returns the caller's username if they are an admin; otherwise throws 403. */
    public String requireAdmin(String authHeader) {
        String username = requireUser(authHeader);
        if (!admins.contains(username))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admins only");
        return username;
    }

    // ---------- reports ----------

    public synchronized Report report(String reporter, String target, String reason) {
        if (target == null || !users.containsKey(target)) throw notFound("No user named " + target);
        if (reporter.equals(target)) throw bad("You can't report yourself");
        boolean alreadyReported = reports.values().stream()
                .anyMatch(r -> r.reporter().equals(reporter) && r.target().equals(target));
        if (alreadyReported) throw bad("You already reported " + target);

        String clean = reason == null ? "" : reason.trim();
        if (clean.length() > 300) throw bad("Reason is too long");

        Report report = new Report(UUID.randomUUID().toString(), reporter, target, clean, System.currentTimeMillis());
        reports.put(report.id(), report);
        return report;
    }

    public List<Report> reports() {
        return reports.values().stream()
                .sorted(Comparator.comparingLong(Report::timestamp))
                .collect(Collectors.toList());
    }

    public synchronized void dismissReport(String id) {
        if (reports.remove(id) == null) throw notFound("Report not found");
    }

    // ---------- files ----------

    public StoredFile saveFile(MultipartFile upload) {
        String id = UUID.randomUUID().toString();
        String original = upload.getOriginalFilename() == null ? "file" : upload.getOriginalFilename();
        String name = original.replaceAll("[\\\\/]", "_");
        String mime = upload.getContentType() == null ? "application/octet-stream" : upload.getContentType();
        Path path = uploadDir.resolve(id);

        try (InputStream in = upload.getInputStream()) {
            Files.copy(in, path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        StoredFile stored = new StoredFile(id, name, mime, upload.getSize(), path);
        files.put(id, stored);
        return stored;
    }

    public StoredFile file(String id) {
        return files.get(id);
    }
}

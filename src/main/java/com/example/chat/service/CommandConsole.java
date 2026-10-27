package com.example.chat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Server commands: help, ban <name>, unban <name>, stop, admin add|remove <name>, admin list.
 * Typed in the server terminal, or sent by ./server.sh cmd ... through the admin endpoint.
 */
@Component
public class CommandConsole {

    private static final String ADMIN_USAGE = "admin add <name> | admin remove <name> | admin list";

    private final Store store;
    private final ApplicationContext context;
    private final boolean terminalEnabled;

    public CommandConsole(Store store, ApplicationContext context,
                          @Value("${chat.console.enabled:true}") boolean terminalEnabled) {
        this.store = store;
        this.context = context;
        this.terminalEnabled = terminalEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!terminalEnabled) return; // server.sh provides its own prompt
        Thread thread = new Thread(this::readLoop, "cmd-console");
        thread.setDaemon(true); // must not keep the JVM alive on its own
        thread.start();
        System.out.println("Command console ready. Type 'help' for a list of commands.");
    }

    private void readLoop() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String output = execute(line);
                if (!output.isEmpty()) System.out.println(output);
            }
        } catch (Exception e) {
            System.out.println("cmd: input closed (" + e.getMessage() + ")");
        }
    }

    /** Runs one command and returns the text to show. Used by the terminal and the admin endpoint. */
    public String execute(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) return "";

        String[] parts = trimmed.split("\\s+");
        String command = parts[0].toLowerCase();

        try {
            return switch (command) {
                case "help", "?" -> helpText();
                case "ban" -> {
                    requireArgs(parts, 2, "ban <name>");
                    store.ban(parts[1]);
                    yield "Banned " + parts[1] + ". Any active sessions were signed out.";
                }
                case "unban" -> {
                    requireArgs(parts, 2, "unban <name>");
                    store.unban(parts[1]);
                    yield "Unbanned " + parts[1] + ".";
                }
                case "stop" -> stopServer();
                case "admin" -> adminCommand(parts);
                default -> "Command not found: " + command + ". Type 'help' for a list of commands.";
            };
        } catch (ResponseStatusException e) {
            return "Error: " + e.getReason();
        } catch (IllegalArgumentException e) {
            return "Usage: " + e.getMessage();
        } catch (RuntimeException e) {
            return "Error: " + e.getMessage();
        }
    }

    private String adminCommand(String[] parts) {
        if (parts.length < 2) throw usage(ADMIN_USAGE);

        return switch (parts[1].toLowerCase()) {
            case "add" -> {
                requireArgs(parts, 3, "admin add <name>");
                store.addAdmin(parts[2]);
                yield parts[2] + " is now an admin.";
            }
            case "remove" -> {
                requireArgs(parts, 3, "admin remove <name>");
                store.removeAdmin(parts[2]);
                yield parts[2] + " is no longer an admin.";
            }
            case "list" -> {
                List<String> admins = store.admins();
                yield admins.isEmpty() ? "No admins." : "Admins: " + String.join(", ", admins);
            }
            default -> throw usage(ADMIN_USAGE);
        };
    }

    private String stopServer() {
        // Stop on a separate thread after a short delay so the caller receives the reply first
        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            int code = SpringApplication.exit((ConfigurableApplicationContext) context);
            System.exit(code);
        }, "server-stop");
        stopper.start();
        return "Stopping Spring Chat server...";
    }

    private static String helpText() {
        return String.join("\n",
                "Commands:",
                "  help, ?               Show this list",
                "  ban <name>            Ban a user: they cannot log in and active sessions end",
                "  unban <name>          Remove a ban",
                "  admin add <name>      Give a user admin rights",
                "  admin remove <name>   Remove admin rights",
                "  admin list            Show all admins",
                "  stop                  Stop the server",
                "  exit                  Stop the server and close the prompt (server.sh prompt only)");
    }

    private static void requireArgs(String[] parts, int count, String usage) {
        if (parts.length < count) throw usage(usage);
    }

    private static IllegalArgumentException usage(String usage) {
        return new IllegalArgumentException(usage);
    }
}

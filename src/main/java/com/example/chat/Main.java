package com.example.chat;

import com.example.chat.controller.HomeController;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Entry point for the Spring Chat server.
 * Run this class (or `mvn spring-boot:run`) to start the application on http://localhost:8080.
 * HomeController is registered explicitly so the chat page at "/" is served when Main starts.
 */
@SpringBootApplication
@Import(HomeController.class)
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}

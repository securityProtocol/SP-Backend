package com.sp.securityprotocol.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HttpIngestController {
    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    @PostMapping("/ingest/http")
    public ResponseEntity<String> ingest(
            @RequestBody(required = false) String body
    ) {
        return ResponseEntity.accepted().body("OK");
    }
}

package com.sp.securityprotocol.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping(value="/echo", consumes="*/*")
    public ResponseEntity<byte[]> echo(
            @RequestHeader HttpHeaders headers,
            @RequestBody(required=false) byte[] body) {

        HttpHeaders out = new HttpHeaders();
        // 클라이언트가 보낸 Content-Type만 그대로 반영
        if (headers.getContentType() != null) {
            out.setContentType(headers.getContentType());
        }
        return new ResponseEntity<>(body == null ? new byte[0] : body, out, HttpStatus.OK);
    }
}

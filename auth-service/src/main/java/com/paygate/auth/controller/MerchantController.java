package com.paygate.auth.controller;

import com.paygate.auth.dto.CreateMerchantRequest;
import com.paygate.auth.dto.MerchantCreatedResponse;
import com.paygate.auth.service.MerchantService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Open in this learning project so you can create test merchants with curl.
 * In a real system this would sit behind an admin login, not be public.
 */
@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final MerchantService service;

    public MerchantController(MerchantService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<MerchantCreatedResponse> create(@Valid @RequestBody CreateMerchantRequest request) {
        return ResponseEntity.status(201).body(service.create(request));
    }
}

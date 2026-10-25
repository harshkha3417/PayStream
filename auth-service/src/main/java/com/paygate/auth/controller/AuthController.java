package com.paygate.auth.controller;

import com.paygate.auth.dto.TokenRequest;
import com.paygate.auth.dto.TokenResponse;
import com.paygate.auth.entity.Merchant;
import com.paygate.auth.service.JwtService;
import com.paygate.auth.service.MerchantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final MerchantService merchants;
    private final JwtService jwt;

    public AuthController(MerchantService merchants, JwtService jwt) {
        this.merchants = merchants;
        this.jwt = jwt;
    }

    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        Merchant merchant = merchants.authenticate(request.merchantId(), request.apiKey());
        String token = jwt.issue(merchant.getId().toString());
        return TokenResponse.bearer(token, jwt.ttlSeconds());
    }
}
package com.paygate.auth.service;

import com.paygate.auth.dto.CreateMerchantRequest;
import com.paygate.auth.dto.MerchantCreatedResponse;
import com.paygate.auth.entity.Merchant;
import com.paygate.auth.exception.InvalidCredentialsException;
import com.paygate.auth.exception.MerchantNotFoundException;
import com.paygate.auth.repository.MerchantRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class MerchantService {

    private final MerchantRepository merchants;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public MerchantService(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    @Transactional
    public MerchantCreatedResponse create(CreateMerchantRequest req) {
        String apiKey = generateApiKey();
        Merchant merchant = merchants.save(new Merchant(req.name(), encoder.encode(apiKey)));
        return new MerchantCreatedResponse(merchant.getId(), merchant.getName(), apiKey);
    }

    @Transactional(readOnly = true)
    public Merchant authenticate(UUID merchantId, String apiKey) {
        Merchant merchant = merchants.findById(merchantId)
                .orElseThrow(InvalidCredentialsException::new); // same error as a wrong key — don't leak existence
        if (!merchant.isActive() || !encoder.matches(apiKey, merchant.getApiKeyHash())) {
            throw new InvalidCredentialsException();
        }
        return merchant;
    }

    @Transactional(readOnly = true)
    public Merchant get(UUID id) {
        return merchants.findById(id).orElseThrow(() -> new MerchantNotFoundException(id));
    }

    private String generateApiKey() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "pk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

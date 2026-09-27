package com.paygate.payment.Dto;

import com.paygate.payment.Enum.PaymentMethod;
import jakarta.validation.constraints.*;

public record CreatePaymentRequest(
        @NotNull @Positive Long amount,          // minor units, e.g. 49900 = INR 499.00
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase code, e.g. INR") String currency,
        @NotNull PaymentMethod method,
        @Size(max = 255) String description
) {

}

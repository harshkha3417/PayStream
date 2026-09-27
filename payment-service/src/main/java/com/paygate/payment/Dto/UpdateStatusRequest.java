package com.paygate.payment.Dto;

import com.paygate.payment.Enum.PaymentStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(@NotNull PaymentStatus status) {
}

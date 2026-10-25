package com.paygate.processor.service;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentSimulatorTest {

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new PaymentSimulator(500, 100, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentSimulator(-1, 100, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentSimulator(10, 100, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentSimulator(10, 100, 1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroFailureRateAlwaysSucceeds() {
        PaymentSimulator simulator = new PaymentSimulator(0, 0, 0.0);
        for (int i = 0; i < 20; i++) {
            assertThat(simulator.process(UUID.randomUUID())).isTrue();
        }
    }

    @Test
    void hundredPercentFailureRateAlwaysFails() {
        PaymentSimulator simulator = new PaymentSimulator(0, 0, 1.0);
        for (int i = 0; i < 20; i++) {
            assertThat(simulator.process(UUID.randomUUID())).isFalse();
        }
    }

    @Test
    void deterministicOutcomeBasedOnRandomRoll() {
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextDouble()).thenReturn(0.05, 0.95);

        // min=0, max=0 so no sleep delay
        PaymentSimulator simulator = new PaymentSimulator(0, 0, 0.1, random);

        // roll 0.05 is < 0.1 failureRate -> should fail
        boolean result1 = simulator.process(UUID.randomUUID());
        assertThat(result1).isFalse();

        // roll 0.95 is >= 0.1 failureRate -> should succeed
        boolean result2 = simulator.process(UUID.randomUUID());
        assertThat(result2).isTrue();
    }
}

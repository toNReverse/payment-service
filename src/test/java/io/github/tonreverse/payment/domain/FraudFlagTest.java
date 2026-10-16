package io.github.tonreverse.payment.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudFlagTest {

    @Test
    void needsARuleAndAReasonForTheReviewer() {
        UUID id = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.parse("2026-12-01T10:15:30Z");

        assertThatThrownBy(() -> new FraudFlag(id, orderId, " ", "amounts differ", now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FraudFlag(id, orderId, "payment_amount_mismatch", "", now))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

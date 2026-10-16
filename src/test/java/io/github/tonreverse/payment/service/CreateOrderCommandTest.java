package io.github.tonreverse.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.service.CreateOrderCommand.Line;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreateOrderCommandTest {

    private static CreateOrderCommand command(String email, Line... lines) {
        return new CreateOrderCommand(email, List.of(lines));
    }

    @Test
    void theFingerprintIsAHexSha256() {
        assertThat(command("anna@example.com", new Line("pandoro", 1)).fingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void lineOrderDoesNotChangeTheFingerprint() {
        CreateOrderCommand one = command("anna@example.com", new Line("pandoro", 2), new Line("panettone-classico", 1));
        CreateOrderCommand other = command("anna@example.com", new Line("panettone-classico", 1), new Line("pandoro", 2));

        assertThat(one.fingerprint()).isEqualTo(other.fingerprint());
    }

    @Test
    void anyChangeInContentChangesTheFingerprint() {
        String original = command("anna@example.com", new Line("pandoro", 2)).fingerprint();

        assertThat(command("anna@example.com", new Line("pandoro", 3)).fingerprint()).isNotEqualTo(original);
        assertThat(command("bruno@example.com", new Line("pandoro", 2)).fingerprint()).isNotEqualTo(original);
        assertThat(command("anna@example.com", new Line("panettone-classico", 2)).fingerprint()).isNotEqualTo(original);
    }
}

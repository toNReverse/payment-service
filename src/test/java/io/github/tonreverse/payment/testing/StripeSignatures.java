package io.github.tonreverse.payment.testing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Builds webhook requests the way Stripe does, so tests can send genuine-looking and forged ones. */
public final class StripeSignatures {

    private StripeSignatures() {}

    /**
     * Stripe's scheme, written out independently of the SDK so tests check it rather than trust it:
     * header "t=<unix seconds>,v1=<hex HMAC-SHA256 of '<t>.<raw body>' keyed with the endpoint secret>".
     */
    public static String sign(String payload, String secret, Instant at) {
        try {
            long timestamp = at.getEpochSecond();
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = hmac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A minimal Stripe event envelope around {@code object}, the JSON of the Stripe object it is about. */
    public static String event(String id, String type, String object) {
        return """
                {"id": "%s", "object": "event", "type": "%s", "livemode": false, "data": {"object": %s}}
                """.formatted(id, type, object);
    }
}

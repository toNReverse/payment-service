package io.github.tonreverse.payment.persistence;

import io.github.tonreverse.payment.domain.FraudFlag;
import java.util.List;
import java.util.UUID;

public interface FraudFlagRepository {

    void insert(FraudFlag flag);

    /** Oldest first. */
    List<FraudFlag> findByOrderId(UUID orderId);
}

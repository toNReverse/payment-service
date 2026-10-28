package io.github.tonreverse.payment.testing;

import io.github.tonreverse.payment.domain.FraudFlag;
import io.github.tonreverse.payment.persistence.FraudFlagRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryFraudFlagRepository implements FraudFlagRepository {

    private final List<FraudFlag> flags = new CopyOnWriteArrayList<>();

    @Override
    public void insert(FraudFlag flag) {
        flags.add(flag);
    }

    @Override
    public List<FraudFlag> findByOrderId(UUID orderId) {
        return flags.stream().filter(flag -> flag.orderId().equals(orderId)).toList();
    }
}

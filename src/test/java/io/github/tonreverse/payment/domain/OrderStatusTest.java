package io.github.tonreverse.payment.domain;

import static io.github.tonreverse.payment.domain.OrderStatus.CREATED;
import static io.github.tonreverse.payment.domain.OrderStatus.EXPIRED;
import static io.github.tonreverse.payment.domain.OrderStatus.FAILED;
import static io.github.tonreverse.payment.domain.OrderStatus.FLAGGED;
import static io.github.tonreverse.payment.domain.OrderStatus.PAID;
import static io.github.tonreverse.payment.domain.OrderStatus.PENDING_PAYMENT;
import static io.github.tonreverse.payment.domain.OrderStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStatusTest {

    record Transition(OrderStatus from, OrderStatus to) {
        @Override
        public String toString() {
            return from + " -> " + to;
        }
    }

    /**
     * The transition table from the spec, written out here independently of OrderStatus, so a mistake
     * in the production table cannot hide behind the test reading the same table.
     */
    private static final Set<Transition> ALLOWED = Set.of(
            new Transition(CREATED, PENDING_PAYMENT),
            new Transition(CREATED, FLAGGED),
            new Transition(PENDING_PAYMENT, PAID),
            new Transition(PENDING_PAYMENT, FAILED),
            new Transition(PENDING_PAYMENT, EXPIRED),
            new Transition(PENDING_PAYMENT, FLAGGED),
            new Transition(PAID, REFUNDED));

    static Stream<Transition> allowedTransitions() {
        return ALLOWED.stream();
    }

    /** Every (from, to) pair of statuses, including staying in the same status, minus the allowed ones. */
    static Stream<Transition> forbiddenTransitions() {
        return Arrays.stream(OrderStatus.values())
                .flatMap(from -> Arrays.stream(OrderStatus.values()).map(to -> new Transition(from, to)))
                .filter(transition -> !ALLOWED.contains(transition));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedTransitions")
    void allowsTransition(Transition transition) {
        assertThat(transition.from().canTransitionTo(transition.to())).isTrue();
        assertThat(transition.from().transitionTo(transition.to())).isEqualTo(transition.to());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forbiddenTransitions")
    void rejectsTransition(Transition transition) {
        assertThat(transition.from().canTransitionTo(transition.to())).isFalse();
        assertThatThrownBy(() -> transition.from().transitionTo(transition.to()))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void coversEveryPairOfStatuses() {
        int statuses = OrderStatus.values().length;

        assertThat(allowedTransitions().count() + forbiddenTransitions().count()).isEqualTo((long) statuses * statuses);
    }

    @Test
    void rejectionIsADomainExceptionThatNamesBothStatuses() {
        assertThatThrownBy(() -> PAID.transitionTo(PENDING_PAYMENT))
                .isInstanceOf(DomainException.class)
                .hasMessageContainingAll("PAID", "PENDING_PAYMENT")
                .satisfies(e -> {
                    InvalidStatusTransitionException rejection = (InvalidStatusTransitionException) e;
                    assertThat(rejection.from()).isEqualTo(PAID);
                    assertThat(rejection.to()).isEqualTo(PENDING_PAYMENT);
                });
    }

    /** Used to tell a webhook that arrives too early (retry later) from one that no longer applies. */
    @ParameterizedTest(name = "{0} can later become {1}: {2}")
    @CsvSource({
            "CREATED, PAID, true",
            "CREATED, REFUNDED, true",
            "PENDING_PAYMENT, REFUNDED, true",
            "PENDING_PAYMENT, PAID, true",
            "PAID, EXPIRED, false",
            "PAID, FAILED, false",
            "PAID, PAID, false",
            "EXPIRED, PAID, false",
            "FLAGGED, PAID, false",
            "REFUNDED, PAID, false"
    })
    void knowsWhichStatusesCanStillBeReached(OrderStatus from, OrderStatus target, boolean reachable) {
        assertThat(from.canEventuallyReach(target)).isEqualTo(reachable);
    }

    @ParameterizedTest
    @EnumSource(names = {"FAILED", "EXPIRED", "REFUNDED", "FLAGGED"})
    void terminalStatusesReachNothing(OrderStatus status) {
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(status.canEventuallyReach(target)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(names = {"FAILED", "EXPIRED", "REFUNDED", "FLAGGED"})
    void finalStatusesAreTerminal(OrderStatus status) {
        assertThat(status.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(names = {"CREATED", "PENDING_PAYMENT", "PAID"})
    void statusesWithAWayOutAreNotTerminal(OrderStatus status) {
        assertThat(status.isTerminal()).isFalse();
    }
}

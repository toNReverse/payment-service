package io.github.tonreverse.payment.api;

import io.github.tonreverse.payment.service.CreateOrderResult;
import io.github.tonreverse.payment.service.OrderService;
import io.github.tonreverse.payment.service.ValidationException;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/** POST /orders and GET /orders/{id}. Parses and validates the request, then hands over to OrderService. */
public final class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    /** Set on a response that repeats an earlier one, as Stripe does. */
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private static final Pattern VISIBLE_ASCII = Pattern.compile("[\\x21-\\x7E]{1,255}");
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    public void create(Context ctx) {
        String key = idempotencyKey(ctx);
        CreateOrderResult result = orders.createOrder(key, parseBody(ctx).toCommand());

        // A replay answers exactly like the first response (201), plus a header saying it is a replay.
        if (result.replayed()) {
            ctx.header(IDEMPOTENT_REPLAYED, "true");
        }
        ctx.status(HttpStatus.CREATED)
                .header("Location", "/orders/" + result.order().id())
                .json(OrderResponse.from(result.order()));
    }

    public void get(Context ctx) {
        String id = ctx.pathParam("id");
        // UUID.fromString alone is too lenient: it accepts "1-2-3-4-5".
        if (!CANONICAL_UUID.matcher(id).matches()) {
            throw new ValidationException("order id must be a UUID");
        }
        ctx.json(OrderResponse.from(orders.getOrder(UUID.fromString(id))));
    }

    private static String idempotencyKey(Context ctx) {
        String key = ctx.header(IDEMPOTENCY_KEY);
        if (key == null || key.isEmpty()) {
            throw new ValidationException(IDEMPOTENCY_KEY + " header is required");
        }
        if (!VISIBLE_ASCII.matcher(key).matches()) {
            throw new ValidationException(IDEMPOTENCY_KEY + " must be 1-255 visible ASCII characters");
        }
        return key;
    }

    /** Turns Jackson's errors into messages that name the field but not our classes. */
    private static CreateOrderRequest parseBody(Context ctx) {
        try {
            CreateOrderRequest request = ctx.bodyAsClass(CreateOrderRequest.class);
            if (request == null) {
                throw new ValidationException("request body must be a JSON object");
            }
            return request;
        } catch (UnrecognizedPropertyException e) {
            throw new ValidationException("unknown field '" + jsonPath(e) + "'");
        } catch (StreamReadException e) {
            // Broken syntax. Checked before JacksonException because it can carry a field path too.
            throw new ValidationException("request body must be valid JSON");
        } catch (JacksonException e) {
            String path = jsonPath(e);
            throw new ValidationException(path.isEmpty() ? "request body must be valid JSON" : path + " has the wrong type");
        }
    }

    /** E.g. "items[0].quantity". */
    private static String jsonPath(JacksonException e) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : e.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}

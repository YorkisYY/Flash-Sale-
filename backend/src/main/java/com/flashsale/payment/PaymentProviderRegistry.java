package com.flashsale.payment;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Looks up a {@link PaymentProvider} by its {@link PaymentProvider#name()} —
 * which is what we persist on {@code Order.provider}.
 *
 * Spring discovers every PaymentProvider bean at startup; the registry indexes
 * them by uppercased name. Callers ({@code DropController}) decide which one
 * to use based on the order's provider field, set from the buyer's choice.
 */
@Component
public class PaymentProviderRegistry {

    private final Map<String, PaymentProvider> byName;

    public PaymentProviderRegistry(List<PaymentProvider> providers) {
        Map<String, PaymentProvider> map = new HashMap<>();
        for (PaymentProvider p : providers) {
            map.put(p.name().toUpperCase(Locale.ROOT), p);
        }
        this.byName = Map.copyOf(map);
    }

    public PaymentProvider require(String name) {
        if (name == null) {
            throw new IllegalArgumentException("provider name is required");
        }
        PaymentProvider p = byName.get(name.toUpperCase(Locale.ROOT));
        if (p == null) {
            throw new IllegalArgumentException(
                    "unknown payment provider '" + name + "'; known: " + byName.keySet());
        }
        return p;
    }
}

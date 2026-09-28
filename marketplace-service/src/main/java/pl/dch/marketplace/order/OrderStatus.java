package pl.dch.marketplace.order;

/**
 * Phase 1 has no payment step, so a created order is simply NEW.
 * Payment-related states are added together with the payment integration.
 */
public enum OrderStatus {
    NEW
}

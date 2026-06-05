package com.flashsale.payment.stripe;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flashsale.payment.stripe")
public class StripeProperties {

    /** Server-side secret key (sk_test_… in test mode). Env: STRIPE_SECRET_KEY. */
    private String secretKey;

    /** Webhook signing secret (whsec_…). Env: STRIPE_WEBHOOK_SECRET. */
    private String webhookSecret;

    /** ISO-4217 currency code. "twd" by default (TWD has no fractional unit). */
    private String currency = "twd";

    /** Frontend result page base; we append /{orderId} on success. */
    private String successUrl;

    /** Where to send the buyer if they cancel mid-checkout. */
    private String cancelUrl;

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getSuccessUrl() { return successUrl; }
    public void setSuccessUrl(String successUrl) { this.successUrl = successUrl; }
    public String getCancelUrl() { return cancelUrl; }
    public void setCancelUrl(String cancelUrl) { this.cancelUrl = cancelUrl; }
}

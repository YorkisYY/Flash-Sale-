package com.flashsale.payment.payuni;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PayUni configuration. All values come from env vars — see .env.example.
 * Never hardcode these.
 */
@ConfigurationProperties(prefix = "flashsale.payment.payuni")
public class PayUniProperties {

    /** PayUni assigned merchant id (MerID / MerchantID — exact field name per PayUni docs). */
    private String merchantId;

    /** AES-256 key. From PayUni merchant console. */
    private String hashKey;

    /** AES-256 IV. From PayUni merchant console. */
    private String hashIv;

    /** "test" or "prod". v1 is test only. */
    private String env = "test";

    /** Server-to-server notify URL (PayUni → backend). */
    private String notifyUrl;

    /** Browser-return URL after payment (PayUni → backend, then redirect to frontend). */
    private String returnUrl;

    /** Frontend result page base (we'll suffix the orderId). */
    private String frontendResultUrl;

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getHashKey() { return hashKey; }
    public void setHashKey(String hashKey) { this.hashKey = hashKey; }
    public String getHashIv() { return hashIv; }
    public void setHashIv(String hashIv) { this.hashIv = hashIv; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public String getNotifyUrl() { return notifyUrl; }
    public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }
    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }
    public String getFrontendResultUrl() { return frontendResultUrl; }
    public void setFrontendResultUrl(String frontendResultUrl) { this.frontendResultUrl = frontendResultUrl; }
}

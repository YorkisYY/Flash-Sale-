package com.flashsale.payment.ecpay;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flashsale.payment.ecpay")
public class EcpayProperties {

    /** Default 3002607 = ECPay's currently-published stage test merchant. */
    private String merchantId;

    /** From ECPay merchant console (test). Env: ECPAY_HASH_KEY. */
    private String hashKey;

    /** From ECPay merchant console (test). Env: ECPAY_HASH_IV. */
    private String hashIv;

    /** Stage endpoint: https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5 */
    private String apiUrl;

    /** Server-to-server notify URL. Must be publicly reachable (use ngrok in local dev). */
    private String returnUrl;

    /** Browser-redirect destination after payment (frontend result page base). */
    private String orderResultUrl;

    /** Where ECPay's "back to merchant" button sends the buyer. */
    private String clientBackUrl;

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getHashKey() { return hashKey; }
    public void setHashKey(String hashKey) { this.hashKey = hashKey; }
    public String getHashIv() { return hashIv; }
    public void setHashIv(String hashIv) { this.hashIv = hashIv; }
    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }
    public String getOrderResultUrl() { return orderResultUrl; }
    public void setOrderResultUrl(String orderResultUrl) { this.orderResultUrl = orderResultUrl; }
    public String getClientBackUrl() { return clientBackUrl; }
    public void setClientBackUrl(String clientBackUrl) { this.clientBackUrl = clientBackUrl; }
}

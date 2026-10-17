package com.premisave.developer.model;

import com.premisave.developer.enums.TransferKind;
import com.premisave.developer.enums.TransferStatus;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A production app being moved to another developer account, or an account asking to use an approved business, waiting
 * for the code that was emailed to the address linked to the business. Only a keyed hash of the code is stored. The
 * row is removed by the database some time after it is finished or has expired.
 */
@Document(collection = "transfer_requests")
public class TransferRequest {

    @Id
    private String id;
    private TransferKind kind;
    private TransferStatus status = TransferStatus.PENDING;

    /** The account that started it and the only one that can confirm it. */
    @Indexed
    private String requesterId;
    /** The account the app or business ends up with. For a claim it is the requester. */
    @Indexed
    private String toDeveloperId;
    private String fromEmail;
    private String toEmail;

    private String appId;
    private String appName;
    private String merchantId;
    private String merchantNumber;
    private String businessName;

    private String otpHash;
    /** The address the code went to, with most of it hidden, for display. */
    private String otpSentTo;
    private int attempts;

    private Instant createdAt;
    private Instant expiresAt;
    private Instant completedAt;

    @Indexed(expireAfter = "0s")
    private Instant purgeAt;

    public TransferRequest() {}

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public TransferKind getKind() { return kind; }
    public void setKind(TransferKind v) { this.kind = v; }
    public TransferStatus getStatus() { return status; }
    public void setStatus(TransferStatus v) { this.status = v; }
    public String getRequesterId() { return requesterId; }
    public void setRequesterId(String v) { this.requesterId = v; }
    public String getToDeveloperId() { return toDeveloperId; }
    public void setToDeveloperId(String v) { this.toDeveloperId = v; }
    public String getFromEmail() { return fromEmail; }
    public void setFromEmail(String v) { this.fromEmail = v; }
    public String getToEmail() { return toEmail; }
    public void setToEmail(String v) { this.toEmail = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public String getAppName() { return appName; }
    public void setAppName(String v) { this.appName = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getMerchantNumber() { return merchantNumber; }
    public void setMerchantNumber(String v) { this.merchantNumber = v; }
    public String getBusinessName() { return businessName; }
    public void setBusinessName(String v) { this.businessName = v; }
    public String getOtpHash() { return otpHash; }
    public void setOtpHash(String v) { this.otpHash = v; }
    public String getOtpSentTo() { return otpSentTo; }
    public void setOtpSentTo(String v) { this.otpSentTo = v; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int v) { this.attempts = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public Instant getPurgeAt() { return purgeAt; }
    public void setPurgeAt(Instant v) { this.purgeAt = v; }
}

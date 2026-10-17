package com.premisave.developer.model;

import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.MethodType;
import com.premisave.developer.enums.SuspendedBy;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Set;

/**
 * An app: one sandbox or production integration of a merchant. Holds identity, status, and the optional narrowing of the
 * merchant's methods and limits. Credentials live in ApiKey, never here.
 */
@Document(collection = "apps")
public class DeveloperApp {

    @Id
    private String appId;

    @Indexed
    private String developerId;
    private String merchantId;
    private Environment environment;
    private String name;
    private String description;

    /** The merchant's own PRM number for a production app, or one of the configured test numbers for a sandbox app. */
    private String merchantNumber;

    /**
     * Production apps only: the Premisave wallet this app went live with. A wallet can be linked to one production app
     * only. The link is a reference, nothing is debited or credited because of it.
     */
    @Indexed(unique = true, sparse = true)
    private String premisaveWalletId;
    private String premisaveWalletAccount;

    private AppStatus status = AppStatus.ACTIVE;
    private SuspendedBy suspendedBy;
    private String suspensionReason;

    /** Null means "same as the merchant". */
    private Set<MethodType> enabledMethods;
    /** Null means "same as the merchant". */
    private Limits limits;

    /** Where payment results are delivered to this app. Delivery itself arrives in a later release. */
    private String callbackUrl;

    /** Counts up with every change; the wallet service uses it to keep the newest copy of the app's settings. */
    private long version;
    /** True while the wallet service has not yet been told about the latest change. */
    @Indexed
    private boolean contextSyncPending;

    private Instant createdAt;
    private Instant updatedAt;

    public DeveloperApp() {}

    /** Records a change: new update time, a version that always goes up, and a note to tell the wallet service. */
    public void touch(Instant now) {
        this.updatedAt = now;
        this.version = Math.max(version + 1, now.toEpochMilli());
        this.contextSyncPending = true;
    }

    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public String getDeveloperId() { return developerId; }
    public void setDeveloperId(String v) { this.developerId = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public Environment getEnvironment() { return environment; }
    public void setEnvironment(Environment v) { this.environment = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getMerchantNumber() { return merchantNumber; }
    public void setMerchantNumber(String v) { this.merchantNumber = v; }
    public String getPremisaveWalletId() { return premisaveWalletId; }
    public void setPremisaveWalletId(String v) { this.premisaveWalletId = v; }
    public String getPremisaveWalletAccount() { return premisaveWalletAccount; }
    public void setPremisaveWalletAccount(String v) { this.premisaveWalletAccount = v; }
    public AppStatus getStatus() { return status; }
    public void setStatus(AppStatus v) { this.status = v; }
    public SuspendedBy getSuspendedBy() { return suspendedBy; }
    public void setSuspendedBy(SuspendedBy v) { this.suspendedBy = v; }
    public String getSuspensionReason() { return suspensionReason; }
    public void setSuspensionReason(String v) { this.suspensionReason = v; }
    public Set<MethodType> getEnabledMethods() { return enabledMethods; }
    public void setEnabledMethods(Set<MethodType> v) { this.enabledMethods = v; }
    public Limits getLimits() { return limits; }
    public void setLimits(Limits v) { this.limits = v; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String v) { this.callbackUrl = v; }
    public long getVersion() { return version; }
    public void setVersion(long v) { this.version = v; }
    public boolean isContextSyncPending() { return contextSyncPending; }
    public void setContextSyncPending(boolean v) { this.contextSyncPending = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}

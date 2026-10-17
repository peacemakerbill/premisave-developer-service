package com.premisave.developer.model;

import com.premisave.developer.enums.DeveloperStatus;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** A person who builds on the platform. Signs in with email and password, or through Google, Facebook or GitHub. */
@Document(collection = "developers")
public class Developer {

    @Id
    private String developerId;

    @Indexed(unique = true)
    private String email;

    /** First, middle and last name. fullName is them joined, kept for display and for older accounts. */
    private String firstName;
    private String middleName;
    private String lastName;
    private String fullName;

    /** Unique, lower case. Generated at sign up and changeable by the owner. */
    @Indexed(unique = true, sparse = true)
    private String username;
    private Instant usernameChangedAt;

    private String addressLine1;
    private String addressLine2;
    private String city;
    private String stateOrRegion;
    private String postalCode;
    /** An IANA zone such as Africa/Nairobi. */
    private String timezone;
    /** Null for an account created through a sign in provider that has not set a password. */
    private String passwordHash;

    /** Given at sign up. Null for accounts created through a sign in provider until the profile is completed. */
    private String country;
    private String phoneNumber;
    private String companyName;
    private Instant termsAcceptedAt;
    private boolean marketingOptIn;

    private boolean emailVerified;
    private Instant emailVerifiedAt;

    /** The profile picture, stored in Cloudinary. The public id is kept so the picture can be replaced or removed. */
    private String profilePictureUrl;
    private String profilePicturePublicId;
    private Instant profilePictureUpdatedAt;

    /** Sign in providers linked to this account, for display. The links themselves are in oauth_identities. */
    private Set<String> authProviders = new LinkedHashSet<>();
    private DeveloperStatus status = DeveloperStatus.ACTIVE;

    /** The wallet service merchant created for this developer, once the business details are given. */
    @Indexed(unique = true, sparse = true)
    private String merchantId;

    private int failedLogins;
    private Instant lockedUntil;

    /** Bumped when the password changes, which ends every token issued before. */
    private int tokenVersion;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastLoginAt;

    public Developer() {}

    public String getDeveloperId() { return developerId; }
    public void setDeveloperId(String v) { this.developerId = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String v) { this.firstName = v; }
    public String getMiddleName() { return middleName; }
    public void setMiddleName(String v) { this.middleName = v; }
    public String getLastName() { return lastName; }
    public void setLastName(String v) { this.lastName = v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public Instant getUsernameChangedAt() { return usernameChangedAt; }
    public void setUsernameChangedAt(Instant v) { this.usernameChangedAt = v; }
    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String v) { this.addressLine1 = v; }
    public String getAddressLine2() { return addressLine2; }
    public void setAddressLine2(String v) { this.addressLine2 = v; }
    public String getCity() { return city; }
    public void setCity(String v) { this.city = v; }
    public String getStateOrRegion() { return stateOrRegion; }
    public void setStateOrRegion(String v) { this.stateOrRegion = v; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String v) { this.postalCode = v; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String v) { this.timezone = v; }
    public String getFullName() { return fullName; }
    public void setFullName(String v) { this.fullName = v; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public String getCountry() { return country; }
    public void setCountry(String v) { this.country = v; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String v) { this.phoneNumber = v; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String v) { this.companyName = v; }
    public Instant getTermsAcceptedAt() { return termsAcceptedAt; }
    public void setTermsAcceptedAt(Instant v) { this.termsAcceptedAt = v; }
    public boolean isMarketingOptIn() { return marketingOptIn; }
    public void setMarketingOptIn(boolean v) { this.marketingOptIn = v; }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean v) { this.emailVerified = v; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public void setEmailVerifiedAt(Instant v) { this.emailVerifiedAt = v; }
    public String getProfilePictureUrl() { return profilePictureUrl; }
    public void setProfilePictureUrl(String v) { this.profilePictureUrl = v; }
    public String getProfilePicturePublicId() { return profilePicturePublicId; }
    public void setProfilePicturePublicId(String v) { this.profilePicturePublicId = v; }
    public Instant getProfilePictureUpdatedAt() { return profilePictureUpdatedAt; }
    public void setProfilePictureUpdatedAt(Instant v) { this.profilePictureUpdatedAt = v; }

    /**
     * Records that the owner proved they control the email address, and activates an account that was waiting for it.
     * A DISABLED account stays disabled.
     */
    public void confirmEmail(Instant now) {
        emailVerified = true;
        emailVerifiedAt = now;
        if (status == DeveloperStatus.PENDING) status = DeveloperStatus.ACTIVE;
    }
    public Set<String> getAuthProviders() { return authProviders; }
    public void setAuthProviders(Set<String> v) { this.authProviders = v == null ? new LinkedHashSet<>() : v; }
    public DeveloperStatus getStatus() { return status; }
    public void setStatus(DeveloperStatus v) { this.status = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public int getFailedLogins() { return failedLogins; }
    public void setFailedLogins(int v) { this.failedLogins = v; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant v) { this.lockedUntil = v; }
    public int getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(int v) { this.tokenVersion = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant v) { this.lastLoginAt = v; }
}

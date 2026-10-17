package com.premisave.developer.service;

import com.premisave.developer.client.WalletApi;
import com.premisave.developer.dto.AdminCreateDeveloperRequest;
import com.premisave.developer.dto.UpdateProfileRequest;
import com.premisave.developer.enums.DeveloperRole;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.repository.DeveloperAppRepository;
import com.premisave.developer.repository.DeveloperRepository;
import com.premisave.developer.repository.EmailTokenRepository;
import com.premisave.developer.repository.OAuthIdentityRepository;
import com.premisave.developer.util.Ids;
import com.premisave.developer.util.PersonNames;
import com.premisave.developer.util.ProfileFields;
import com.premisave.developer.view.AdminViews;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Staff control of developer accounts: create, read, update, role, status, sign out, unlock, password reset, email, business
 * sharing and delete. Every change is recorded in the audit trail. An administrator cannot change their own role or status
 * or delete their own account, and the last active administrator can never be demoted, disabled or deleted, so staff cannot
 * lock themselves out of the platform.
 */
@Service("developerAdminDeveloperService")
public class AdminDeveloperService {

    private final DeveloperRepository developers;
    private final DeveloperService accounts;
    private final BusinessService businesses;
    private final DeveloperAppRepository apps;
    private final WalletApi wallet;
    private final AccountEmailService accountEmail;
    private final ProfilePictureService pictures;
    private final EmailTokenRepository emailTokens;
    private final OAuthIdentityRepository identities;
    private final AdminAudit audit;
    private final Clock clock;

    @Autowired
    public AdminDeveloperService(DeveloperRepository developers, DeveloperService accounts, BusinessService businesses,
                                 DeveloperAppRepository apps, WalletApi wallet, AccountEmailService accountEmail,
                                 ProfilePictureService pictures, EmailTokenRepository emailTokens,
                                 OAuthIdentityRepository identities, AdminAudit audit) {
        this(developers, accounts, businesses, apps, wallet, accountEmail, pictures, emailTokens, identities, audit,
                Clock.systemUTC());
    }

    public AdminDeveloperService(DeveloperRepository developers, DeveloperService accounts, BusinessService businesses,
                                 DeveloperAppRepository apps, WalletApi wallet, AccountEmailService accountEmail,
                                 ProfilePictureService pictures, EmailTokenRepository emailTokens,
                                 OAuthIdentityRepository identities, AdminAudit audit, Clock clock) {
        this.developers = developers;
        this.accounts = accounts;
        this.businesses = businesses;
        this.apps = apps;
        this.wallet = wallet;
        this.accountEmail = accountEmail;
        this.pictures = pictures;
        this.emailTokens = emailTokens;
        this.identities = identities;
        this.audit = audit;
        this.clock = clock;
    }

    // Reading

    public Page<Developer> search(String text, DeveloperStatus status, Pageable pageable) {
        return accounts.search(text, status, pageable);
    }

    public Page<Developer> administrators(Pageable pageable) {
        return developers.findByRole(DeveloperRole.ADMIN, pageable);
    }

    public Developer get(String developerId) {
        return accounts.require(developerId);
    }

    // Create and update

    public Map<String, Object> create(AdminActor actor, AdminCreateDeveloperRequest req) {
        String email = DeveloperService.normaliseEmail(req.email());
        String first = PersonNames.required("firstName", req.firstName(), 60);
        String middle = PersonNames.optional("middleName", req.middleName(), 60);
        String last = PersonNames.required("lastName", req.lastName(), 60);
        String country = req.country() == null || req.country().isBlank() ? null : ProfileFields.country(req.country());
        String phone = ProfileFields.phone(req.phoneNumber());
        String company = ProfileFields.text("companyName", req.companyName(), 160);
        DeveloperRole role = role(req.role() == null || req.role().isBlank() ? DeveloperRole.DEVELOPER.name() : req.role());
        if (developers.findByEmail(email).isPresent()) throw emailTaken();

        Instant now = clock.instant();
        Developer d = new Developer();
        d.setDeveloperId(Ids.next("dev"));
        d.setEmail(email);
        d.setFirstName(first);
        d.setMiddleName(middle);
        d.setLastName(last);
        d.setFullName(PersonNames.full(first, middle, last));
        d.setCountry(country);
        d.setPhoneNumber(phone);
        d.setCompanyName(company);
        d.setRole(role);
        d.setStatus(DeveloperStatus.ACTIVE);
        d.confirmEmail(now);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        for (int attempt = 0; ; attempt++) {
            d.setUsername(accounts.generateUsername(first, last, email));
            try {
                developers.insert(d);
                break;
            } catch (DuplicateKeyException raced) {
                if (attempt >= 3 || developers.findByEmail(email).isPresent()) throw emailTaken();
            }
        }
        boolean invite = !Boolean.FALSE.equals(req.sendInvite());
        if (invite) accountEmail.forgotPassword(email);
        audit.record(actor, "DEVELOPER_CREATED", "DEVELOPER", d.getDeveloperId(), "role " + role + (invite ? ", invitation sent" : ""));
        Map<String, Object> out = AdminViews.developer(d);
        out.put("inviteSent", invite && accountEmail.canSendEmail());
        return out;
    }

    public Developer updateProfile(AdminActor actor, String developerId, UpdateProfileRequest req) {
        Developer d = accounts.require(developerId);
        accounts.updateProfile(d, req);
        audit.record(actor, "DEVELOPER_UPDATED", "DEVELOPER", developerId, "profile changed");
        return d;
    }

    /** Changes the sign in address. verified (default true) counts it as confirmed; false sends a confirmation link. */
    public Developer setEmail(AdminActor actor, String developerId, String rawEmail, Boolean verified) {
        Developer d = accounts.require(developerId);
        String email = DeveloperService.normaliseEmail(rawEmail);
        developers.findByEmail(email).filter(other -> !other.getDeveloperId().equals(developerId)).ifPresent(other -> {
            throw emailTaken();
        });
        boolean confirmed = !Boolean.FALSE.equals(verified);
        Instant now = clock.instant();
        d.setEmail(email);
        d.setEmailVerified(confirmed);
        d.setEmailVerifiedAt(confirmed ? now : null);
        d.setTokenVersion(d.getTokenVersion() + 1);
        d.setUpdatedAt(now);
        try {
            developers.save(d);
        } catch (DuplicateKeyException raced) {
            throw emailTaken();
        }
        if (!confirmed) accountEmail.sendVerification(d);
        audit.record(actor, "DEVELOPER_EMAIL_CHANGED", "DEVELOPER", developerId, "email changed, " + (confirmed ? "confirmed" : "confirmation sent"));
        return d;
    }

    // Role and status

    public Developer setRole(AdminActor actor, String developerId, String rawRole) {
        DeveloperRole role = role(rawRole);
        Developer d = accounts.require(developerId);
        if (actor.is(developerId)) {
            throw new DeveloperException(ErrorCode.FORBIDDEN, "You cannot change your own role");
        }
        if (d.getRole() == role) return d;
        if (role == DeveloperRole.ADMIN && d.getStatus() != DeveloperStatus.ACTIVE) {
            throw DeveloperException.invalidState("Only an active account can be made an administrator");
        }
        if (role == DeveloperRole.DEVELOPER) requireAnotherAdmin(d, "demoted");
        d.setRole(role);
        d.setUpdatedAt(clock.instant());
        developers.save(d);
        audit.record(actor, "DEVELOPER_ROLE_CHANGED", "DEVELOPER", developerId, "role is now " + role);
        return d;
    }

    public Developer setStatus(AdminActor actor, String developerId, DeveloperStatus status) {
        Developer d = accounts.require(developerId);
        if (status != DeveloperStatus.ACTIVE) {
            if (actor.is(developerId)) throw new DeveloperException(ErrorCode.FORBIDDEN, "You cannot disable your own account");
            requireAnotherAdmin(d, "disabled");
        }
        Developer saved = accounts.setStatus(developerId, status);
        audit.record(actor, status == DeveloperStatus.ACTIVE ? "DEVELOPER_ENABLED" : "DEVELOPER_DISABLED", "DEVELOPER", developerId,
                "status is now " + status);
        return saved;
    }

    // Account care

    /** Counts the email address as confirmed, which also activates an account that was waiting for it. */
    public Developer verifyEmail(AdminActor actor, String developerId) {
        Developer d = accounts.require(developerId);
        Instant now = clock.instant();
        d.confirmEmail(now);
        d.setUpdatedAt(now);
        developers.save(d);
        audit.record(actor, "DEVELOPER_EMAIL_CONFIRMED", "DEVELOPER", developerId, "email counted as confirmed");
        return d;
    }

    /** Clears a sign in lockout and the count of failed attempts. */
    public Developer unlock(AdminActor actor, String developerId) {
        Developer d = accounts.require(developerId);
        d.setFailedLogins(0);
        d.setLockedUntil(null);
        d.setUpdatedAt(clock.instant());
        developers.save(d);
        audit.record(actor, "DEVELOPER_UNLOCKED", "DEVELOPER", developerId, "lockout cleared");
        return d;
    }

    /** Ends every session of the account, on every device. */
    public Developer signOut(AdminActor actor, String developerId) {
        Developer d = accounts.require(developerId);
        accounts.logoutEverywhere(d);
        audit.record(actor, "DEVELOPER_SIGNED_OUT", "DEVELOPER", developerId, "all sessions ended");
        return d;
    }

    /** Emails the person a link to choose a new password. The link only goes to an active account. */
    public Map<String, Object> sendPasswordReset(AdminActor actor, String developerId) {
        Developer d = accounts.require(developerId);
        boolean sent = d.getStatus() == DeveloperStatus.ACTIVE && accountEmail.canSendEmail();
        if (d.getStatus() == DeveloperStatus.ACTIVE) accountEmail.forgotPassword(d.getEmail());
        audit.record(actor, "DEVELOPER_PASSWORD_RESET_SENT", "DEVELOPER", developerId, sent ? "link requested" : "not sent");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("developerId", developerId);
        m.put("requested", d.getStatus() == DeveloperStatus.ACTIVE);
        m.put("emailEnabled", accountEmail.canSendEmail());
        return m;
    }

    /**
     * Removes an account that owns nothing. One that still has apps, or applied for a business, must be disabled instead,
     * because the wallet service keeps those records and they must not be left without an owner.
     */
    public void delete(AdminActor actor, String developerId) {
        Developer d = accounts.require(developerId);
        if (actor.is(developerId)) throw new DeveloperException(ErrorCode.FORBIDDEN, "You cannot delete your own account");
        requireAnotherAdmin(d, "deleted");
        long owned = apps.countByDeveloperId(developerId);
        if (owned > 0) {
            throw DeveloperException.invalidState("This developer still has " + owned + " apps. Move or delete them first, or disable the account")
                    .with("apps", owned);
        }
        if (!d.appliedBusinessIds().isEmpty()) {
            throw DeveloperException.invalidState("This developer applied for businesses, which stay in the wallet service. Disable the account instead")
                    .with("businessIds", d.appliedBusinessIds());
        }
        pictures.remove(d);
        emailTokens.deleteByDeveloperId(developerId);
        identities.deleteByDeveloperId(developerId);
        developers.deleteById(developerId);
        audit.record(actor, "DEVELOPER_DELETED", "DEVELOPER", developerId, d.getEmail());
    }

    // Businesses

    /** Every business the account can use, with where each application stands. */
    public Map<String, Object> businessesOf(String developerId) {
        return businesses.list(accounts.require(developerId));
    }

    /** Gives the account use of a business it did not apply for. The business must exist in the wallet service. */
    public Map<String, Object> shareBusiness(AdminActor actor, String developerId, String businessId) {
        Developer d = accounts.require(developerId);
        wallet.getMerchantView(businessId);
        businesses.share(d, businessId);
        audit.record(actor, "BUSINESS_SHARED", "BUSINESS", businessId, "shared with " + developerId);
        return businesses.view(d, businessId);
    }

    /** Takes back the use of a business that was shared. A business the account applied for cannot be taken from it. */
    public void unshareBusiness(AdminActor actor, String developerId, String businessId) {
        Developer d = accounts.require(developerId);
        businesses.requireOwned(d, businessId);
        if (d.appliedFor(businessId)) {
            throw DeveloperException.invalidState("This account applied for the business, so it cannot be taken away");
        }
        long used = apps.countByDeveloperIdAndMerchantId(developerId, businessId);
        if (used > 0) {
            throw DeveloperException.invalidState("This account still has " + used + " apps on the business. Move or delete them first")
                    .with("apps", used);
        }
        d.removeSharedBusiness(businessId);
        d.setUpdatedAt(clock.instant());
        developers.save(d);
        audit.record(actor, "BUSINESS_UNSHARED", "BUSINESS", businessId, "no longer shared with " + developerId);
    }

    // Rules

    /** Refuses when the account is the last active administrator, because losing it would leave nobody to run the platform. */
    private void requireAnotherAdmin(Developer d, String verb) {
        if (d.isAdmin() && d.getStatus() == DeveloperStatus.ACTIVE
                && developers.countByRoleAndStatus(DeveloperRole.ADMIN, DeveloperStatus.ACTIVE) <= 1) {
            throw DeveloperException.invalidState("This is the last active administrator and cannot be " + verb);
        }
    }

    static DeveloperRole role(String value) {
        try {
            return DeveloperRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw DeveloperException.validation("role", "must be DEVELOPER or ADMIN");
        }
    }

    private static DeveloperException emailTaken() {
        return new DeveloperException(ErrorCode.EMAIL_TAKEN, "An account with this email already exists");
    }
}

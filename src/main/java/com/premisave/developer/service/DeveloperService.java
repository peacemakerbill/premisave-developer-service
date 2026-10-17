package com.premisave.developer.service;

import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.dto.ChangePasswordRequest;
import com.premisave.developer.dto.ClientInfo;
import com.premisave.developer.dto.LoginRequest;
import com.premisave.developer.dto.SignupRequest;
import com.premisave.developer.dto.UpdateProfileRequest;
import com.premisave.developer.enums.DeveloperRole;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.RevokedToken;
import com.premisave.developer.repository.DeveloperRepository;
import com.premisave.developer.repository.RevokedTokenRepository;
import com.premisave.developer.security.JwtService;
import com.premisave.developer.security.PasswordHasher;
import com.premisave.developer.util.Ids;
import com.premisave.developer.util.PasswordPolicy;
import com.premisave.developer.util.PersonNames;
import com.premisave.developer.util.ProfileFields;
import com.premisave.developer.util.SearchText;
import com.premisave.developer.util.Usernames;
import com.premisave.developer.view.DeveloperViews;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Sign up, sign in, sign out and password change for developers. */
@Service("developerAccountService")
public class DeveloperService {

    private static final Logger log = LoggerFactory.getLogger(DeveloperService.class);

    /** How long to wait between two username changes. The first change after sign up is free. */
    static final Duration USERNAME_COOLDOWN = Duration.ofDays(7);

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");

    private final DeveloperRepository developers;
    private final PasswordHasher hasher;
    private final JwtService jwt;
    private final DeveloperProperties.Security security;
    private final RevokedTokenRepository revoked;
    private final AccountEmailService accountEmail;
    private final Clock clock;
    private final String dummyHash;

    /** A verified sign in token together with the developer it belongs to. */
    public record Authenticated(Developer developer, JwtService.Claims claims) {}

    @Autowired
    public DeveloperService(DeveloperRepository developers, PasswordHasher hasher, JwtService jwt,
                            DeveloperProperties props, RevokedTokenRepository revoked, AccountEmailService accountEmail) {
        this(developers, hasher, jwt, props.security(), revoked, accountEmail, Clock.systemUTC());
    }

    public DeveloperService(DeveloperRepository developers, PasswordHasher hasher, JwtService jwt,
                            DeveloperProperties.Security security, RevokedTokenRepository revoked,
                            AccountEmailService accountEmail, Clock clock) {
        this.developers = developers;
        this.hasher = hasher;
        this.jwt = jwt;
        this.security = security;
        this.revoked = revoked;
        this.accountEmail = accountEmail;
        this.clock = clock;
        // Checked when an email is unknown, so a miss costs the same as a wrong password.
        this.dummyHash = hasher.hash("not-a-real-password");
    }

    public Map<String, Object> signup(SignupRequest req) {
        String email = normaliseEmail(req.email());
        checkPassword(req.password(), email);
        String first = PersonNames.required("firstName", req.firstName(), 60);
        String middle = PersonNames.optional("middleName", req.middleName(), 60);
        String last = PersonNames.required("lastName", req.lastName(), 60);
        if (!Boolean.TRUE.equals(req.acceptTerms())) {
            throw DeveloperException.validation("acceptTerms", "must be accepted to create an account");
        }
        String country = ProfileFields.country(req.country());
        String phone = ProfileFields.phone(req.phoneNumber());
        String company = ProfileFields.text("companyName", req.companyName(), 160);
        if (developers.findByEmail(email).isPresent()) throw emailTaken();

        Instant now = clock.instant();
        Developer d = new Developer();
        d.setDeveloperId(Ids.next("dev"));
        d.setEmail(email);
        d.setFirstName(first);
        d.setMiddleName(middle);
        d.setLastName(last);
        d.setFullName(PersonNames.full(first, middle, last));
        d.setPasswordHash(hasher.hash(req.password()));
        d.setCountry(country);
        d.setPhoneNumber(phone);
        d.setCompanyName(company);
        d.setTermsAcceptedAt(now);
        d.setMarketingOptIn(Boolean.TRUE.equals(req.marketingOptIn()));
        // Stays PENDING until the email address is confirmed.
        d.setStatus(DeveloperStatus.PENDING);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        // The unique index decides between two sign ups that pick the same username at once: the loser tries again
        // with a new one, unless the clash was the email address.
        for (int attempt = 0; ; attempt++) {
            d.setUsername(generateUsername(first, last, email));
            try {
                developers.insert(d);
                break;
            } catch (DuplicateKeyException raced) {
                if (attempt >= 3 || developers.findByEmail(email).isPresent()) throw emailTaken();
            }
        }
        accountEmail.sendVerification(d);
        return signupResult(d);
    }

    /**
     * What sign up answers: a message the portal can show, the next step and the account status. No token is issued and
     * no account data is returned. The account is PENDING until the email address is confirmed, and it cannot sign in
     * before that. The portal tells the developer to open the confirmation link.
     */
    private Map<String, Object> signupResult(Developer d) {
        boolean emailed = accountEmail.canSendEmail();
        String message = emailed
                ? "Your account has been created and is pending. We sent a confirmation link to " + d.getEmail()
                  + ". Confirm your email address, then sign in."
                : "Your account has been created and is pending. Your email address must be confirmed before you can sign in. "
                  + "Ask for a new confirmation link.";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", message);
        m.put("next", "CONFIRM_EMAIL");
        m.put("status", d.getStatus().name());
        m.put("confirmationEmailSent", emailed);
        return m;
    }

    public Map<String, Object> login(LoginRequest req) {
        return login(req, ClientInfo.UNKNOWN);
    }

    public Map<String, Object> login(LoginRequest req, ClientInfo client) {
        String email = normaliseEmail(req.email());
        Instant now = clock.instant();
        Developer d = developers.findByEmail(email).orElse(null);
        if (d == null) {
            hasher.verify(req.password(), dummyHash);
            throw invalid();
        }
        if (d.getLockedUntil() != null && now.isBefore(d.getLockedUntil())) {
            long minutes = Math.max(1, Duration.between(now, d.getLockedUntil()).toMinutes() + 1);
            throw new DeveloperException(ErrorCode.ACCOUNT_LOCKED,
                    "Too many failed sign in attempts. Try again in about " + minutes + " minutes.");
        }
        boolean passwordOk;
        if (d.getPasswordHash() == null) {
            // Created through a sign in provider and never given a password. Costs the same as a wrong password.
            hasher.verify(req.password(), dummyHash);
            passwordOk = false;
        } else {
            passwordOk = hasher.verify(req.password(), d.getPasswordHash());
        }
        if (!passwordOk) {
            int failed = d.getFailedLogins() + 1;
            if (failed >= security.maxFailedLogins()) {
                d.setLockedUntil(now.plus(Duration.ofMinutes(security.lockMinutes())));
                failed = 0;
            }
            d.setFailedLogins(failed);
            developers.save(d);
            throw invalid();
        }
        if (d.getStatus() == DeveloperStatus.DISABLED) {
            throw new DeveloperException(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled");
        }
        // Only said after the password was right, so it tells nothing to someone who does not know it.
        if (d.getStatus() == DeveloperStatus.PENDING) throw pending(d);
        d.setFailedLogins(0);
        d.setLockedUntil(null);
        d.setLastLoginAt(now);
        fillMissingProfile(d);
        if (hasher.needsRehash(d.getPasswordHash())) d.setPasswordHash(hasher.hash(req.password()));
        developers.save(d);
        accountEmail.sendSignInAlert(d, client, "Email and password", now);
        return session(d);
    }

    /**
     * Updates the profile. Only the fields that are sent change, and nothing changes unless every field sent is valid.
     * A username change is checked for format, reserved words and uniqueness, and may be made once a week.
     */
    public Map<String, Object> updateProfile(Developer d, UpdateProfileRequest req) {
        Instant now = clock.instant();
        String first = req.firstName() == null ? d.getFirstName() : PersonNames.required("firstName", req.firstName(), 60);
        String middle = req.middleName() == null ? d.getMiddleName() : PersonNames.optional("middleName", req.middleName(), 60);
        String last = req.lastName() == null ? d.getLastName() : PersonNames.required("lastName", req.lastName(), 60);
        String country = req.country() == null ? d.getCountry() : ProfileFields.country(req.country());
        String phone = req.phoneNumber() == null ? d.getPhoneNumber() : ProfileFields.phone(req.phoneNumber());
        String company = req.companyName() == null ? d.getCompanyName() : ProfileFields.text("companyName", req.companyName(), 160);
        String line1 = req.addressLine1() == null ? d.getAddressLine1() : ProfileFields.text("addressLine1", req.addressLine1(), 120);
        String line2 = req.addressLine2() == null ? d.getAddressLine2() : ProfileFields.text("addressLine2", req.addressLine2(), 120);
        String city = req.city() == null ? d.getCity() : ProfileFields.text("city", req.city(), 80);
        String region = req.stateOrRegion() == null ? d.getStateOrRegion() : ProfileFields.text("stateOrRegion", req.stateOrRegion(), 80);
        String postal = req.postalCode() == null ? d.getPostalCode() : ProfileFields.postalCode(req.postalCode());
        String zone = req.timezone() == null ? d.getTimezone() : ProfileFields.timezone(req.timezone());

        String username = d.getUsername();
        boolean usernameChanged = false;
        if (req.username() != null) {
            String wanted = Usernames.normalise(req.username());
            if (!wanted.equals(d.getUsername())) {
                Usernames.check(wanted);
                if (d.getUsernameChangedAt() != null && now.isBefore(d.getUsernameChangedAt().plus(USERNAME_COOLDOWN))) {
                    long wait = Duration.between(now, d.getUsernameChangedAt().plus(USERNAME_COOLDOWN)).toSeconds() + 1;
                    throw new DeveloperException(ErrorCode.RATE_LIMITED,
                            "The username was changed recently. Try again in " + Math.max(1, wait / 3600) + " hours.")
                            .with("retryAfterSeconds", wait);
                }
                if (developers.findByUsername(wanted).isPresent()) throw usernameTaken();
                username = wanted;
                usernameChanged = true;
            }
        }

        d.setFirstName(first);
        d.setMiddleName(middle);
        d.setLastName(last);
        d.setFullName(PersonNames.full(first, middle, last));
        d.setCountry(country);
        d.setPhoneNumber(phone);
        d.setCompanyName(company);
        d.setAddressLine1(line1);
        d.setAddressLine2(line2);
        d.setCity(city);
        d.setStateOrRegion(region);
        d.setPostalCode(postal);
        d.setTimezone(zone);
        if (req.marketingOptIn() != null) d.setMarketingOptIn(req.marketingOptIn());
        if (usernameChanged) {
            d.setUsername(username);
            d.setUsernameChangedAt(now);
        }
        d.setUpdatedAt(now);
        try {
            developers.save(d);
        } catch (DuplicateKeyException raced) {
            throw usernameTaken();
        }
        return DeveloperViews.developer(d);
    }

    /** Whether a username can be used by this developer, and if not, why. Needs a signed in developer. */
    public Map<String, Object> checkUsername(Developer d, String raw) {
        String wanted = Usernames.normalise(raw);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", wanted);
        if (wanted.equals(d.getUsername())) {
            m.put("available", true);
            m.put("reason", "CURRENT");
            return m;
        }
        String reason = "OK";
        try {
            Usernames.check(wanted);
            if (developers.existsByUsername(wanted)) reason = "TAKEN";
        } catch (DeveloperException e) {
            reason = Usernames.isValid(wanted) ? "RESERVED" : "INVALID";
            m.put("message", e.getMessage());
        }
        m.put("available", reason.equals("OK"));
        m.put("reason", reason);
        return m;
    }

    /** A username that is valid, not reserved and not taken, built from the names (see Usernames.base). */
    public String generateUsername(String first, String last, String email) {
        String base = Usernames.base(first, last, email);
        // A name that is itself reserved (or contains a brand) starts from the neutral "dev" instead.
        if (Usernames.isReserved(base)) base = "dev";
        for (int attempt = 0; attempt < 40; attempt++) {
            String candidate = Usernames.candidate(base, attempt);
            if (Usernames.isValid(candidate) && !Usernames.isReserved(candidate) && !developers.existsByUsername(candidate)) {
                return candidate;
            }
        }
        throw new DeveloperException(ErrorCode.INTERNAL_ERROR, "Could not create a username. Try again.");
    }

    /**
     * Accounts made before names and usernames existed, or through a sign in provider, may lack them. Fills what can be
     * worked out: the names from the full name, and a generated username. The caller saves.
     */
    public void fillMissingProfile(Developer d) {
        if (d.getFirstName() == null && d.getFullName() != null) {
            PersonNames.Parts parts = PersonNames.split(d.getFullName());
            d.setFirstName(parts.first());
            d.setMiddleName(parts.middle());
            d.setLastName(parts.last());
        }
        if (d.getUsername() == null) {
            d.setUsername(generateUsername(d.getFirstName(), d.getLastName(), d.getEmail()));
        }
    }

    /**
     * Changes the password and ends every other session. An account that has no password yet (created through a sign
     * in provider) may set one without giving a current password.
     */
    public Map<String, Object> changePassword(Developer d, ChangePasswordRequest req) {
        if (d.getPasswordHash() != null) {
            if (req.currentPassword() == null || !hasher.verify(req.currentPassword(), d.getPasswordHash())) {
                throw new DeveloperException(ErrorCode.INVALID_CREDENTIALS, "The current password is not correct");
            }
        }
        checkPassword(req.newPassword(), d.getEmail());
        d.setPasswordHash(hasher.hash(req.newPassword()));
        d.setTokenVersion(d.getTokenVersion() + 1);
        d.setUpdatedAt(clock.instant());
        developers.save(d);
        accountEmail.sendPasswordChanged(d);
        return session(d);
    }

    /** Ends the token that made this request. Other sessions of the developer keep working. */
    public void logout(Developer d, JwtService.Claims claims) {
        if (claims.tokenId() == null || claims.tokenId().isEmpty()) return;
        revoked.save(new RevokedToken(claims.tokenId(), d.getDeveloperId(), Instant.ofEpochSecond(claims.expiresAt())));
    }

    /** Ends every token issued so far, on every device. */
    public void logoutEverywhere(Developer d) {
        d.setTokenVersion(d.getTokenVersion() + 1);
        d.setUpdatedAt(clock.instant());
        developers.save(d);
    }

    /** The developer behind a bearer token, or an UNAUTHENTICATED error. */
    public Developer authenticate(String bearerToken) {
        return authenticateToken(bearerToken).developer();
    }

    public Authenticated authenticateToken(String bearerToken) {
        JwtService.Claims claims = jwt.verify(bearerToken)
                .orElseThrow(() -> new DeveloperException(ErrorCode.UNAUTHENTICATED, "Sign in again"));
        Developer d = developers.findById(claims.developerId())
                .orElseThrow(() -> new DeveloperException(ErrorCode.UNAUTHENTICATED, "Sign in again"));
        if (d.getTokenVersion() != claims.tokenVersion()) {
            throw new DeveloperException(ErrorCode.UNAUTHENTICATED, "Sign in again");
        }
        if (!claims.tokenId().isEmpty() && revoked.existsById(claims.tokenId())) {
            throw new DeveloperException(ErrorCode.UNAUTHENTICATED, "Sign in again");
        }
        if (d.getStatus() == DeveloperStatus.DISABLED) {
            throw new DeveloperException(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled");
        }
        if (d.getStatus() == DeveloperStatus.PENDING) throw pending(d);
        return new Authenticated(d, claims);
    }

    public Developer require(String developerId) {
        return developers.findById(developerId)
                .orElseThrow(() -> new DeveloperException(ErrorCode.NOT_FOUND, "Developer not found"));
    }

    /**
     * Staff search over developer accounts: text is matched anywhere in the email, username, full name or company name,
     * or equals the developer id exactly. Either filter may be left out; with neither, every account is listed.
     */
    public Page<Developer> search(String text, DeveloperStatus status, Pageable pageable) {
        String q = SearchText.clean(text);
        if (q == null) {
            return status == null ? developers.findAll(pageable) : developers.findByStatus(status, pageable);
        }
        String pattern = SearchText.escape(q);
        return status == null
                ? developers.search(pattern, q, pageable)
                : developers.searchByStatus(pattern, q, status, pageable);
    }

    public Developer setStatus(String developerId, DeveloperStatus status) {
        if (status == DeveloperStatus.PENDING) throw DeveloperException.validation("status", "must be ACTIVE or DISABLED");
        Developer d = require(developerId);
        d.setStatus(status);
        d.setUpdatedAt(clock.instant());
        return developers.save(d);
    }

    /** A new sign in for the developer: the token and the account. */
    public Map<String, Object> session(Developer d) {
        promoteIfListed(d);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accessToken", jwt.issue(d.getDeveloperId(), d.getTokenVersion()));
        m.put("tokenType", "Bearer");
        m.put("expiresIn", jwt.ttlSeconds());
        m.put("developer", DeveloperViews.developer(d));
        return m;
    }

    /**
     * Makes the account an administrator when its address is on the configured list and the address has been confirmed.
     * This is how the first administrator comes about; the confirmed address is what stops anyone else from claiming it.
     */
    private void promoteIfListed(Developer d) {
        if (d.isAdmin() || !d.isEmailVerified() || !security.isAdminEmail(d.getEmail())) return;
        d.setRole(DeveloperRole.ADMIN);
        d.setUpdatedAt(clock.instant());
        developers.save(d);
        log.info("Developer {} became an administrator through the configured address list", d.getDeveloperId());
    }

    public static String normaliseEmail(String email) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(e).matches()) throw DeveloperException.validation("email", "is not a valid email address");
        return e;
    }

    private void checkPassword(String password, String email) {
        PasswordPolicy.check(security.passwordMinLength(), password, email);
    }

    /** What a pending account is told when it tries to sign in, or to use a token it was given before. */
    public static DeveloperException pending(Developer d) {
        return new DeveloperException(ErrorCode.ACCOUNT_PENDING,
                "Your account is pending. Confirm your email address with the link we sent to " + d.getEmail()
                        + ", then sign in. You can ask for a new link.")
                .with("next", "CONFIRM_EMAIL");
    }

    private static DeveloperException invalid() {
        return new DeveloperException(ErrorCode.INVALID_CREDENTIALS, "The email or password is not correct");
    }

    private static DeveloperException usernameTaken() {
        return new DeveloperException(ErrorCode.USERNAME_TAKEN, "This username is already taken");
    }

    private static DeveloperException emailTaken() {
        return new DeveloperException(ErrorCode.EMAIL_TAKEN, "An account with this email already exists");
    }
}

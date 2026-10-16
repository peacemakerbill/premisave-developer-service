package com.premisave.developer.developer;

import com.premisave.developer.auth.AccountEmailService;
import com.premisave.developer.auth.RevokedToken;
import com.premisave.developer.auth.RevokedTokenRepository;
import com.premisave.developer.common.DeveloperException;
import com.premisave.developer.common.Ids;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.security.JwtService;
import com.premisave.developer.security.PasswordHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
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
        if (developers.findByEmail(email).isPresent()) throw emailTaken();

        Instant now = clock.instant();
        Developer d = new Developer();
        d.setDeveloperId(Ids.next("dev"));
        d.setEmail(email);
        d.setFullName(req.fullName().trim());
        d.setPasswordHash(hasher.hash(req.password()));
        d.setStatus(DeveloperStatus.ACTIVE);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        try {
            developers.insert(d);
        } catch (DuplicateKeyException raced) {
            throw emailTaken();
        }
        accountEmail.sendVerification(d);
        return session(d);
    }

    public Map<String, Object> login(LoginRequest req) {
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
        d.setFailedLogins(0);
        d.setLockedUntil(null);
        d.setLastLoginAt(now);
        if (hasher.needsRehash(d.getPasswordHash())) d.setPasswordHash(hasher.hash(req.password()));
        developers.save(d);
        return session(d);
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
        return new Authenticated(d, claims);
    }

    public Developer require(String developerId) {
        return developers.findById(developerId)
                .orElseThrow(() -> new DeveloperException(ErrorCode.NOT_FOUND, "Developer not found"));
    }

    public Developer setStatus(String developerId, DeveloperStatus status) {
        Developer d = require(developerId);
        d.setStatus(status);
        d.setUpdatedAt(clock.instant());
        return developers.save(d);
    }

    /** A new sign in for the developer: the token and the account. */
    public Map<String, Object> session(Developer d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accessToken", jwt.issue(d.getDeveloperId(), d.getTokenVersion()));
        m.put("tokenType", "Bearer");
        m.put("expiresIn", jwt.ttlSeconds());
        m.put("developer", DeveloperViews.developer(d));
        return m;
    }

    public static String normaliseEmail(String email) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(e).matches()) throw DeveloperException.validation("email", "is not a valid email address");
        return e;
    }

    private void checkPassword(String password, String email) {
        PasswordPolicy.check(security.passwordMinLength(), password, email);
    }

    private static DeveloperException invalid() {
        return new DeveloperException(ErrorCode.INVALID_CREDENTIALS, "The email or password is not correct");
    }

    private static DeveloperException emailTaken() {
        return new DeveloperException(ErrorCode.EMAIL_TAKEN, "An account with this email already exists");
    }
}

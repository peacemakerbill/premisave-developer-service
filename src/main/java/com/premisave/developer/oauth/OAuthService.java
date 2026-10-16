package com.premisave.developer.oauth;

import com.premisave.developer.auth.AccountEmailService;
import com.premisave.developer.common.DeveloperException;
import com.premisave.developer.common.Ids;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.developer.Developer;
import com.premisave.developer.developer.DeveloperRepository;
import com.premisave.developer.developer.DeveloperService;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sign up and sign in with Google, Facebook or GitHub. One flow serves both: a returning identity signs in, a new
 * address creates an account.
 *
 * An existing account is joined to a provider only when the provider vouches for the address. If that account was
 * created with a password and never confirmed, the password is removed at the same time, so whoever typed the address
 * in first (and may not own it) cannot keep a way in. A provider that does not vouch for its addresses (Facebook) never
 * joins an existing account.
 */
@Service("developerOAuthService")
public class OAuthService {

    private final OAuthClient client;
    private final OAuthState states;
    private final OAuthIdentityRepository identities;
    private final DeveloperRepository developers;
    private final DeveloperService accounts;
    private final AccountEmailService accountEmail;
    private final DeveloperProperties.OAuth config;
    private final Clock clock;

    @Autowired
    public OAuthService(OAuthIdentityRepository identities, DeveloperRepository developers, DeveloperService accounts,
                        AccountEmailService accountEmail, DeveloperProperties props) {
        this(new OAuthClient(props.oauth(), new JdkHttpTransport()),
                new OAuthState(props.jwt().secret(), props.oauth().stateTtlMinutes(), Clock.systemUTC()),
                identities, developers, accounts, accountEmail, props.oauth(), Clock.systemUTC());
    }

    public OAuthService(OAuthClient client, OAuthState states, OAuthIdentityRepository identities,
                        DeveloperRepository developers, DeveloperService accounts, AccountEmailService accountEmail,
                        DeveloperProperties.OAuth config, Clock clock) {
        this.client = client;
        this.states = states;
        this.identities = identities;
        this.developers = developers;
        this.accounts = accounts;
        this.accountEmail = accountEmail;
        this.config = config;
        this.clock = clock;
    }

    /** Which providers the portal may offer. */
    public Map<String, Object> providers() {
        List<String> on = new ArrayList<>();
        for (OAuthProvider p : OAuthProvider.values()) {
            if (client.enabled(p)) on.add(p.key());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providers", on);
        return m;
    }

    /** The address to send the person to, and the state to keep for the return. */
    public Map<String, Object> authorize(String providerName, String redirectUri) {
        OAuthProvider p = enabled(providerName);
        String uri = allowedRedirect(redirectUri);
        String state = states.create(p, uri);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", p.key());
        m.put("authorizationUrl", client.authorizationUrl(p, uri, state));
        m.put("state", state);
        return m;
    }

    /** Finishes the flow: checks the state, reads the profile, and signs the person in or up. */
    public Map<String, Object> callback(String providerName, String code, String state, String redirectUri) {
        OAuthProvider p = enabled(providerName);
        String uri = allowedRedirect(redirectUri);
        states.verify(state, p, uri);
        OAuthProfile profile = client.fetchProfile(p, code, uri);
        if (profile.subject() == null || profile.subject().isBlank()) {
            throw new DeveloperException(ErrorCode.OAUTH_FAILED, "The provider did not identify you. Start again.");
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return signInOrUp(profile);
            } catch (DuplicateKeyException raced) {
                // Two sign ins for a new person arrived together. The second finds the account the first made.
                if (attempt >= 1) throw new DeveloperException(ErrorCode.OAUTH_FAILED, "Sign in did not complete. Try again.");
            }
        }
    }

    private Map<String, Object> signInOrUp(OAuthProfile profile) {
        Instant now = clock.instant();
        String identityId = OAuthIdentity.idOf(profile.provider(), profile.subject());

        Optional<OAuthIdentity> known = identities.findById(identityId);
        if (known.isPresent()) {
            Developer d = developers.findById(known.get().getDeveloperId())
                    .orElseThrow(() -> new DeveloperException(ErrorCode.OAUTH_FAILED, "That account no longer exists."));
            return finish(d, false, profile, now);
        }

        String email = null;
        if (profile.email() != null) {
            try {
                email = DeveloperService.normaliseEmail(profile.email());
            } catch (DeveloperException invalid) {
                email = null;
            }
        }
        if (email == null) {
            throw new DeveloperException(ErrorCode.OAUTH_EMAIL_REQUIRED, "We need an email address from "
                    + OAuthClient.label(profile.provider()) + ". Make one public and verified there, or sign up with your email.");
        }

        Optional<Developer> existing = developers.findByEmail(email);
        if (existing.isPresent()) {
            Developer d = existing.get();
            if (!profile.emailVerified()) {
                throw new DeveloperException(ErrorCode.ACCOUNT_EXISTS,
                        "An account with this email already exists. Sign in with your password or the method you used before.");
            }
            if (d.getStatus() == DeveloperStatus.DISABLED) {
                throw new DeveloperException(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled");
            }
            if (!d.isEmailVerified()) {
                // Nobody has proved they own this address yet. The provider has, so the password that was typed in
                // without proof is dropped and every earlier token ends.
                d.setPasswordHash(null);
                d.setTokenVersion(d.getTokenVersion() + 1);
                d.setFailedLogins(0);
                d.setLockedUntil(null);
                d.setEmailVerified(true);
                d.setEmailVerifiedAt(now);
            }
            link(d, profile, email, now);
            return finish(d, false, profile, now);
        }

        Developer d = new Developer();
        d.setDeveloperId(Ids.next("dev"));
        d.setEmail(email);
        d.setFullName(displayName(profile.name(), email));
        d.setPasswordHash(null);
        d.setStatus(DeveloperStatus.ACTIVE);
        d.setEmailVerified(profile.emailVerified());
        d.setEmailVerifiedAt(profile.emailVerified() ? now : null);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        d.getAuthProviders().add(profile.provider().key());
        developers.insert(d);
        saveIdentity(d, profile, email, now);
        if (!d.isEmailVerified()) accountEmail.sendVerification(d);
        return finish(d, true, profile, now);
    }

    private void link(Developer d, OAuthProfile profile, String email, Instant now) {
        d.getAuthProviders().add(profile.provider().key());
        d.setUpdatedAt(now);
        developers.save(d);
        saveIdentity(d, profile, email, now);
    }

    private void saveIdentity(Developer d, OAuthProfile profile, String email, Instant now) {
        OAuthIdentity id = new OAuthIdentity();
        id.setId(OAuthIdentity.idOf(profile.provider(), profile.subject()));
        id.setDeveloperId(d.getDeveloperId());
        id.setProvider(profile.provider().key());
        id.setEmail(email);
        id.setLinkedAt(now);
        identities.insert(id);
    }

    private Map<String, Object> finish(Developer d, boolean created, OAuthProfile profile, Instant now) {
        if (d.getStatus() == DeveloperStatus.DISABLED) {
            throw new DeveloperException(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled");
        }
        d.setLastLoginAt(now);
        d.setFailedLogins(0);
        d.setLockedUntil(null);
        developers.save(d);
        Map<String, Object> m = new LinkedHashMap<>(accounts.session(d));
        m.put("newAccount", created);
        m.put("provider", profile.provider().key());
        return m;
    }

    private OAuthProvider enabled(String name) {
        OAuthProvider p = OAuthProvider.parse(name);
        if (!client.enabled(p)) {
            throw new DeveloperException(ErrorCode.OAUTH_NOT_CONFIGURED, "Sign in with " + OAuthClient.label(p) + " is not available");
        }
        return p;
    }

    private String allowedRedirect(String redirectUri) {
        if (redirectUri == null || !config.isAllowedRedirect(redirectUri)) {
            throw DeveloperException.validation("redirectUri", "is not one of the allowed return addresses");
        }
        return redirectUri.trim();
    }

    private static String displayName(String name, String email) {
        String n = name == null ? "" : name.trim();
        if (n.length() < 2) n = email.substring(0, email.indexOf('@'));
        if (n.length() < 2) n = email;
        return n.length() > 120 ? n.substring(0, 120) : n;
    }
}

package com.premisave.developer.service;

import com.premisave.developer.client.JdkHttpTransport;
import com.premisave.developer.client.OAuthClient;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.dto.ClientInfo;
import com.premisave.developer.dto.OAuthProfile;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.OAuthProvider;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.OAuthIdentity;
import com.premisave.developer.repository.DeveloperRepository;
import com.premisave.developer.repository.OAuthIdentityRepository;
import com.premisave.developer.util.Ids;
import com.premisave.developer.util.PersonNames;

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
 * Sign up and sign in with Google, Facebook or GitHub. The browser gets the provider's token (or, for GitHub, its one
 * time code) and posts it here. One flow serves both: a returning identity signs in, a new address creates an account.
 *
 * An existing account is joined to a provider only when the provider vouches for the address. If that account was
 * created with a password and never confirmed, the password is removed at the same time, so whoever typed the address
 * in first (and may not own it) cannot keep a way in. A provider that does not vouch for its addresses (Facebook) never
 * joins an existing account.
 */
@Service("developerOAuthService")
public class OAuthService {

    private final OAuthClient client;
    private final OAuthIdentityRepository identities;
    private final DeveloperRepository developers;
    private final DeveloperService accounts;
    private final AccountEmailService accountEmail;
    private final Clock clock;

    @Autowired
    public OAuthService(OAuthIdentityRepository identities, DeveloperRepository developers, DeveloperService accounts,
                        AccountEmailService accountEmail, DeveloperProperties props) {
        this(new OAuthClient(props.oauth(), new JdkHttpTransport()), identities, developers, accounts, accountEmail,
                Clock.systemUTC());
    }

    public OAuthService(OAuthClient client, OAuthIdentityRepository identities, DeveloperRepository developers,
                        DeveloperService accounts, AccountEmailService accountEmail, Clock clock) {
        this.client = client;
        this.identities = identities;
        this.developers = developers;
        this.accounts = accounts;
        this.accountEmail = accountEmail;
        this.clock = clock;
    }

    /**
     * Which providers are on, and the public client id the browser needs for each. A client id is not a secret: it is
     * in the address of every provider's login page.
     */
    public Map<String, Object> providers() {
        List<String> on = new ArrayList<>();
        Map<String, Object> ids = new LinkedHashMap<>();
        for (OAuthProvider p : OAuthProvider.values()) {
            if (client.enabled(p)) {
                on.add(p.key());
                ids.put(p.key(), client.clientId(p));
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providers", on);
        m.put("clientIds", ids);
        return m;
    }

    /** Signs in or up with a token the browser got from the provider. */
    public Map<String, Object> signIn(String providerName, String token) {
        return signIn(providerName, token, ClientInfo.UNKNOWN);
    }

    /**
     * Checks the token with the provider, reads the profile, and signs the person in or up. token is Google's ID token,
     * Facebook's access token or GitHub's one time code.
     */
    public Map<String, Object> signIn(String providerName, String token, ClientInfo caller) {
        OAuthProvider p = enabled(providerName);
        if (token == null || token.isBlank()) {
            throw DeveloperException.validation("token", "is required");
        }
        OAuthProfile profile = client.fetchProfile(p, token.trim());
        if (profile.subject() == null || profile.subject().isBlank()) {
            throw new DeveloperException(ErrorCode.OAUTH_FAILED, "The provider did not identify you. Start again.");
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return signInOrUp(profile, caller);
            } catch (DuplicateKeyException raced) {
                // Two sign ins for a new person arrived together. The second finds the account the first made.
                if (attempt >= 1) throw new DeveloperException(ErrorCode.OAUTH_FAILED, "Sign in did not complete. Try again.");
            }
        }
    }

    private Map<String, Object> signInOrUp(OAuthProfile profile, ClientInfo caller) {
        Instant now = clock.instant();
        String identityId = OAuthIdentity.idOf(profile.provider(), profile.subject());

        Optional<OAuthIdentity> known = identities.findById(identityId);
        if (known.isPresent()) {
            Developer d = developers.findById(known.get().getDeveloperId())
                    .orElseThrow(() -> new DeveloperException(ErrorCode.OAUTH_FAILED, "That account no longer exists."));
            return finish(d, false, profile, now, caller);
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
                d.confirmEmail(now);
            }
            link(d, profile, email, now);
            return finish(d, false, profile, now, caller);
        }

        Developer d = new Developer();
        d.setDeveloperId(Ids.next("dev"));
        d.setEmail(email);
        d.setFullName(displayName(profile.name(), email));
        PersonNames.Parts names = PersonNames.split(d.getFullName());
        d.setFirstName(names.first());
        d.setMiddleName(names.middle());
        d.setLastName(names.last());
        d.setUsername(accounts.generateUsername(names.first(), names.last(), email));
        d.setPasswordHash(null);
        // A provider that vouches for the address activates the account at once. Otherwise it waits for the email link,
        // and cannot sign in until then.
        d.setStatus(DeveloperStatus.PENDING);
        if (profile.emailVerified()) d.confirmEmail(now);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        d.getAuthProviders().add(profile.provider().key());
        developers.insert(d);
        saveIdentity(d, profile, email, now);
        if (!d.isEmailVerified()) accountEmail.sendVerification(d);
        return finish(d, true, profile, now, caller);
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

    private Map<String, Object> finish(Developer d, boolean created, OAuthProfile profile, Instant now, ClientInfo caller) {
        if (d.getStatus() == DeveloperStatus.DISABLED) {
            throw new DeveloperException(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled");
        }
        if (d.getStatus() == DeveloperStatus.PENDING) {
            // A provider that does not vouch for the address (Facebook) leaves the account waiting for the email link.
            if (created) {
                throw new DeveloperException(ErrorCode.ACCOUNT_PENDING, "Your account has been created and is pending. "
                        + "We sent a confirmation link to " + d.getEmail() + ". Confirm your email address, then sign in with "
                        + OAuthClient.label(profile.provider()) + " again.").with("next", "CONFIRM_EMAIL");
            }
            throw DeveloperService.pending(d);
        }
        d.setLastLoginAt(now);
        accounts.fillMissingProfile(d);
        d.setFailedLogins(0);
        d.setLockedUntil(null);
        developers.save(d);
        if (created) {
            if (d.isEmailVerified()) accountEmail.sendWelcome(d);
        } else {
            accountEmail.sendSignInAlert(d, caller, OAuthClient.label(profile.provider()), now);
        }
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

    private static String displayName(String name, String email) {
        String n = name == null ? "" : name.trim();
        if (n.length() < 2) n = email.substring(0, email.indexOf('@'));
        if (n.length() < 2) n = email;
        return n.length() > 120 ? n.substring(0, 120) : n;
    }
}

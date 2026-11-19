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
import com.premisave.developer.model.EmailToken;
import com.premisave.developer.model.OAuthIdentity;
import com.premisave.developer.repository.DeveloperRepository;
import com.premisave.developer.repository.OAuthIdentityRepository;
import com.premisave.developer.util.Ids;
import com.premisave.developer.util.MaskedEmail;
import com.premisave.developer.util.PersonNames;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sign up and sign in with Google, Facebook or GitHub. The browser gets the provider's token (or, for GitHub, its one
 * time code) and posts it here. One flow serves both: a returning identity signs in, a new address creates an account.
 *
 * An existing account is joined to a provider on the provider's word only when the provider vouches for the address
 * (Google and GitHub do). If that account was created with a password and never confirmed, the password is removed at
 * the same time, so whoever typed the address in first (and may not own it) cannot keep a way in.
 *
 * A provider that does not vouch for its addresses (Facebook) cannot join an existing account on its own word. The
 * address of that account is emailed a link instead, and whoever owns the mailbox joins the two by opening it. After
 * that the provider signs in like any other. A signed in developer can also connect and disconnect a provider from the
 * profile, which needs no email because the developer has already proved who they are.
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
        return signIn(providerName, token, null, null, caller);
    }

    /**
     * Same, with an authorization code in place of a token. The code is traded with the provider here, using the
     * redirect address it was issued for.
     */
    public Map<String, Object> signIn(String providerName, String token, String code, String redirectUri, ClientInfo caller) {
        OAuthProvider p = enabled(providerName);
        OAuthProfile profile = profileOf(p, token, code, redirectUri);
        for (int attempt = 0; ; attempt++) {
            try {
                return signInOrUp(profile, caller);
            } catch (DuplicateKeyException raced) {
                // Two sign ins for a new person arrived together. The second finds the account the first made.
                if (attempt >= 1) throw new DeveloperException(ErrorCode.OAUTH_FAILED, "Sign in did not complete. Try again.");
            }
        }
    }

    /** Asks the provider who the token or code belongs to. */
    private OAuthProfile profileOf(OAuthProvider p, String token, String code, String redirectUri) {
        boolean hasToken = token != null && !token.isBlank();
        boolean hasCode = code != null && !code.isBlank();
        if (!hasToken && !hasCode) {
            throw DeveloperException.validation("token", "or code is required");
        }
        OAuthProfile profile = hasToken
                ? client.fetchProfile(p, token.trim())
                : client.fetchProfileFromCode(p, code.trim(), redirectUri == null ? null : redirectUri.trim());
        if (profile.subject() == null || profile.subject().isBlank()) {
            throw new DeveloperException(ErrorCode.OAUTH_FAILED, "The provider did not identify you. Start again.");
        }
        return profile;
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
                throw linkByEmail(d, profile);
            }
            if (d.getStatus() == DeveloperStatus.DISABLED) {
                throw new DeveloperException(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled");
            }
            // Nobody may have proved they own this address yet. The provider has, so a password typed in without
            // proof is dropped and every earlier token ends.
            claimUnconfirmed(d, now);
            link(d, profile.provider(), profile.subject(), email, now);
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
        saveIdentity(d, profile.provider(), profile.subject(), email, now);
        if (!d.isEmailVerified()) accountEmail.sendVerification(d);
        return finish(d, true, profile, now, caller);
    }

    /**
     * The provider gave an address that already has an account, and it does not vouch for the address. The account's own
     * mailbox gets a link to join the two. The answer says so, and never signs anyone in.
     */
    private DeveloperException linkByEmail(Developer d, OAuthProfile profile) {
        OAuthProvider p = profile.provider();
        String label = OAuthClient.label(p);
        String plain = "An account with this email already exists. Sign in with your password or the method you used before.";
        if (d.getStatus() == DeveloperStatus.DISABLED) {
            return new DeveloperException(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled");
        }
        if (!accountEmail.canSendEmail()) return new DeveloperException(ErrorCode.ACCOUNT_EXISTS, plain);
        String where = MaskedEmail.of(d.getEmail());
        Optional<Long> wait = accountEmail.sendProviderLink(d, p, profile.subject());
        if (wait.isPresent()) {
            return new DeveloperException(ErrorCode.ACCOUNT_EXISTS, "A link to join " + label + " to this account went to "
                    + where + " a moment ago. Open it, or try again in " + wait.get() + " seconds.")
                    .with("next", "CONFIRM_LINK").with("retryAfterSeconds", wait.get());
        }
        return new DeveloperException(ErrorCode.ACCOUNT_EXISTS, "An account with this email already exists. We sent a link to "
                + where + ". Open it to join " + label + " to that account, then sign in with " + label + " again.")
                .with("next", "CONFIRM_LINK");
    }

    /**
     * Redeems the link from the email. It proves the mailbox is the person's, so the provider account is joined to the
     * developer. It does not sign anyone in: the person goes back and signs in with the provider.
     */
    public Map<String, Object> confirmLink(String token) {
        EmailToken row = accountEmail.redeemProviderLink(token);
        Developer d = developers.findById(row.getDeveloperId()).orElseThrow(OAuthService::invalidLink);
        if (!d.getEmail().equals(row.getEmail()) || row.getProvider() == null || row.getSubject() == null) throw invalidLink();
        if (d.getStatus() == DeveloperStatus.DISABLED) {
            throw new DeveloperException(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled");
        }
        OAuthProvider p = OAuthProvider.parse(row.getProvider());
        Instant now = clock.instant();
        String id = OAuthIdentity.idOf(p, row.getSubject());
        Optional<OAuthIdentity> known = identities.findById(id);
        if (known.isPresent() && !known.get().getDeveloperId().equals(d.getDeveloperId())) throw elsewhere(p);
        if (known.isEmpty()) {
            ensureNoOtherAccount(d, p);
            claimUnconfirmed(d, now);
            try {
                link(d, p, row.getSubject(), d.getEmail(), now);
            } catch (DuplicateKeyException raced) {
                if (!sameDeveloper(id, d)) throw elsewhere(p);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("linked", true);
        m.put("provider", p.key());
        m.put("email", d.getEmail());
        return m;
    }

    /** What a signed in developer has connected. */
    public Map<String, Object> connections(Developer d) {
        List<Map<String, Object>> rows = new ArrayList<>();
        identities.findByDeveloperId(d.getDeveloperId()).stream()
                .sorted(Comparator.comparing(OAuthIdentity::getProvider))
                .forEach(i -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("provider", i.getProvider());
                    r.put("email", i.getEmail());
                    r.put("linkedAt", i.getLinkedAt() == null ? null : i.getLinkedAt().toString());
                    rows.add(r);
                });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connections", rows);
        m.put("hasPassword", d.getPasswordHash() != null);
        m.put("available", providers().get("providers"));
        return m;
    }

    /**
     * Connects a provider account to the signed in developer. The provider's address does not have to match: the
     * developer is already signed in and has just proved they own the provider account too.
     */
    public Map<String, Object> connect(Developer d, String providerName, String token, String code, String redirectUri) {
        OAuthProvider p = enabled(providerName);
        OAuthProfile profile = profileOf(p, token, code, redirectUri);
        String id = OAuthIdentity.idOf(p, profile.subject());
        Optional<OAuthIdentity> known = identities.findById(id);
        if (known.isPresent()) {
            if (!known.get().getDeveloperId().equals(d.getDeveloperId())) throw elsewhere(p);
            return connections(d);
        }
        ensureNoOtherAccount(d, p);
        String email = d.getEmail();
        if (profile.email() != null) {
            try {
                email = DeveloperService.normaliseEmail(profile.email());
            } catch (DeveloperException invalid) {
                email = d.getEmail();
            }
        }
        try {
            link(d, p, profile.subject(), email, clock.instant());
        } catch (DuplicateKeyException raced) {
            if (!sameDeveloper(id, d)) throw elsewhere(p);
        }
        return connections(d);
    }

    /** Removes a provider from the signed in developer. At least one way to sign in must be left. */
    public Map<String, Object> disconnect(Developer d, String providerName) {
        OAuthProvider p = OAuthProvider.parse(providerName);
        List<OAuthIdentity> mine = identities.findByDeveloperId(d.getDeveloperId());
        List<OAuthIdentity> these = mine.stream().filter(i -> p.key().equals(i.getProvider())).toList();
        if (these.isEmpty()) {
            throw new DeveloperException(ErrorCode.NOT_FOUND, OAuthClient.label(p) + " is not connected to this account");
        }
        if (d.getPasswordHash() == null && mine.size() == these.size()) {
            throw DeveloperException.invalidState("Set a password first. Without one, " + OAuthClient.label(p)
                    + " is the only way to sign in to this account.");
        }
        for (OAuthIdentity i : these) identities.delete(i);
        d.getAuthProviders().remove(p.key());
        d.setUpdatedAt(clock.instant());
        developers.save(d);
        return connections(d);
    }

    /** One account at each provider per developer, so a disconnect always means one thing. */
    private void ensureNoOtherAccount(Developer d, OAuthProvider p) {
        boolean has = identities.findByDeveloperId(d.getDeveloperId()).stream().anyMatch(i -> p.key().equals(i.getProvider()));
        if (has) {
            throw DeveloperException.invalidState("A different " + OAuthClient.label(p)
                    + " account is already connected to this account. Disconnect it first.");
        }
    }

    private boolean sameDeveloper(String identityId, Developer d) {
        return identities.findById(identityId).map(i -> i.getDeveloperId().equals(d.getDeveloperId())).orElse(false);
    }

    private static DeveloperException elsewhere(OAuthProvider p) {
        return new DeveloperException(ErrorCode.ACCOUNT_EXISTS, "That " + OAuthClient.label(p)
                + " account is already connected to a different Premisave developer account.");
    }

    private static DeveloperException invalidLink() {
        return new DeveloperException(ErrorCode.INVALID_TOKEN, "This link is invalid or has expired. Sign in with the provider again to get a new one.");
    }

    /** An account whose address nobody has confirmed loses its password: whoever typed it in never proved they own the mailbox. */
    private void claimUnconfirmed(Developer d, Instant now) {
        if (d.isEmailVerified()) return;
        d.setPasswordHash(null);
        d.setTokenVersion(d.getTokenVersion() + 1);
        d.setFailedLogins(0);
        d.setLockedUntil(null);
        d.confirmEmail(now);
    }

    private void link(Developer d, OAuthProvider provider, String subject, String email, Instant now) {
        d.getAuthProviders().add(provider.key());
        d.setUpdatedAt(now);
        developers.save(d);
        saveIdentity(d, provider, subject, email, now);
    }

    private void saveIdentity(Developer d, OAuthProvider provider, String subject, String email, Instant now) {
        OAuthIdentity id = new OAuthIdentity();
        id.setId(OAuthIdentity.idOf(provider, subject));
        id.setDeveloperId(d.getDeveloperId());
        id.setProvider(provider.key());
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

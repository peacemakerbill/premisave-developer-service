package com.premisave.developer;

import com.premisave.developer.app.DeveloperApp;
import com.premisave.developer.app.DeveloperAppRepository;
import com.premisave.developer.developer.Developer;
import com.premisave.developer.developer.DeveloperRepository;
import com.premisave.developer.key.ApiKey;
import com.premisave.developer.key.ApiKeyRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In memory repositories with their stores exposed. */
public final class TestRepos {

    private TestRepos() {}

    public static final class Keys {
        public final Map<String, ApiKey> store = new LinkedHashMap<>();
        public final ApiKeyRepository repo = Fake.of(ApiKeyRepository.class, Map.of(
                "findById", a -> Optional.ofNullable(store.get((String) a[0])),
                "findByAppId", a -> store.values().stream().filter(k -> k.getAppId().equals(a[0])).toList(),
                "insert", a -> put((ApiKey) a[0]),
                "save", a -> put((ApiKey) a[0])));

        private ApiKey put(ApiKey k) {
            store.put(k.getKey(), k);
            return k;
        }
    }

    public static final class Apps {
        public final Map<String, DeveloperApp> store = new LinkedHashMap<>();
        public final DeveloperAppRepository repo = Fake.of(DeveloperAppRepository.class, Map.of(
                "findById", a -> Optional.ofNullable(store.get((String) a[0])),
                "insert", a -> put((DeveloperApp) a[0]),
                "save", a -> put((DeveloperApp) a[0]),
                "countByDeveloperIdAndEnvironment", a -> store.values().stream()
                        .filter(x -> x.getDeveloperId().equals(a[0]) && x.getEnvironment() == a[1]).count(),
                "findByPremisaveWalletId", a -> store.values().stream()
                        .filter(x -> a[0].equals(x.getPremisaveWalletId())).findFirst(),
                "findByContextSyncPendingTrue", a -> store.values().stream().filter(DeveloperApp::isContextSyncPending).toList()));

        private DeveloperApp put(DeveloperApp x) {
            store.put(x.getAppId(), x);
            return x;
        }
    }

    public static final class Developers {
        public final Map<String, Developer> store = new LinkedHashMap<>();
        public final DeveloperRepository repo = Fake.of(DeveloperRepository.class, Map.of(
                "findById", a -> Optional.ofNullable(store.get((String) a[0])),
                "findByEmail", a -> store.values().stream().filter(d -> d.getEmail().equals(a[0])).findFirst(),
                "insert", a -> put((Developer) a[0]),
                "save", a -> put((Developer) a[0])));

        private Developer put(Developer d) {
            store.put(d.getDeveloperId(), d);
            return d;
        }
    }

    public static final class EmailTokens {
        public final Map<String, com.premisave.developer.auth.EmailToken> store = new LinkedHashMap<>();
        public final com.premisave.developer.auth.EmailTokenRepository repo = Fake.of(com.premisave.developer.auth.EmailTokenRepository.class, Map.of(
                "insert", a -> {
                    com.premisave.developer.auth.EmailToken x = (com.premisave.developer.auth.EmailToken) a[0];
                    store.put(x.getId(), x);
                    return x;
                },
                "findByTokenHash", a -> store.values().stream().filter(x -> x.getTokenHash().equals(a[0])).findFirst(),
                "deleteByTokenHash", a -> {
                    long before = store.size();
                    store.values().removeIf(x -> x.getTokenHash().equals(a[0]));
                    return before - store.size();
                },
                "deleteByDeveloperIdAndType", a -> {
                    long before = store.size();
                    store.values().removeIf(x -> x.getDeveloperId().equals(a[0]) && x.getType() == a[1]);
                    return before - store.size();
                },
                "findFirstByDeveloperIdAndTypeOrderByCreatedAtDesc", a -> store.values().stream()
                        .filter(x -> x.getDeveloperId().equals(a[0]) && x.getType() == a[1])
                        .max(java.util.Comparator.comparing(com.premisave.developer.auth.EmailToken::getCreatedAt)),
                "countByDeveloperIdAndTypeAndCreatedAtAfter", a -> store.values().stream()
                        .filter(x -> x.getDeveloperId().equals(a[0]) && x.getType() == a[1]
                                && x.getCreatedAt().isAfter((java.time.Instant) a[2])).count()));
    }

    public static final class Revoked {
        public final Map<String, com.premisave.developer.auth.RevokedToken> store = new LinkedHashMap<>();
        public final com.premisave.developer.auth.RevokedTokenRepository repo = Fake.of(com.premisave.developer.auth.RevokedTokenRepository.class, Map.of(
                "save", a -> {
                    com.premisave.developer.auth.RevokedToken x = (com.premisave.developer.auth.RevokedToken) a[0];
                    store.put(x.getTokenId(), x);
                    return x;
                },
                "existsById", a -> store.containsKey((String) a[0])));
    }

    public static final class Identities {
        public final Map<String, com.premisave.developer.oauth.OAuthIdentity> store = new LinkedHashMap<>();
        public final com.premisave.developer.oauth.OAuthIdentityRepository repo = Fake.of(com.premisave.developer.oauth.OAuthIdentityRepository.class, Map.of(
                "findById", a -> java.util.Optional.ofNullable(store.get((String) a[0])),
                "insert", a -> {
                    com.premisave.developer.oauth.OAuthIdentity x = (com.premisave.developer.oauth.OAuthIdentity) a[0];
                    if (store.containsKey(x.getId())) throw new org.springframework.dao.DuplicateKeyException("duplicate");
                    store.put(x.getId(), x);
                    return x;
                }));
    }

    /** Remembers every email handed to it. */
    public static final class RecordingMailer implements com.premisave.developer.auth.Mailer {
        public record Sent(String to, String subject, String html) {}
        public final java.util.List<Sent> sent = new java.util.ArrayList<>();
        public boolean fail;
        @Override
        public void send(String to, String subject, String html) throws Exception {
            if (fail) throw new Exception("smtp down");
            sent.add(new Sent(to, subject, html));
        }
        /** The first address in the most recent email that starts with the given prefix. */
        public String lastLink(String prefix) {
            String html = sent.get(sent.size() - 1).html();
            int i = html.indexOf(prefix);
            int end = html.indexOf('"', i);
            return html.substring(i, end);
        }
    }
}

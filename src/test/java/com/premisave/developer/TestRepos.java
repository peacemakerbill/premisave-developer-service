package com.premisave.developer;

import com.premisave.developer.model.ApiKey;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.DeveloperApp;
import com.premisave.developer.repository.ApiKeyRepository;
import com.premisave.developer.repository.DeveloperAppRepository;
import com.premisave.developer.repository.DeveloperRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In memory repositories with their stores exposed. */
public final class TestRepos {

    private TestRepos() {}

    /** One page of an already ordered list, as a Spring Data Page. */
    static <T> org.springframework.data.domain.Page<T> page(java.util.List<T> ordered, org.springframework.data.domain.Pageable p) {
        int from = Math.min(ordered.size(), p.getPageNumber() * p.getPageSize());
        int to = Math.min(ordered.size(), from + p.getPageSize());
        return new org.springframework.data.domain.PageImpl<>(ordered.subList(from, to), p, ordered.size());
    }

    /** Whether the whole value matches a pattern the way the database's anchored or open regex would. */
    static boolean like(Object value, String pattern, int flags) {
        return value != null && java.util.regex.Pattern.compile(pattern, flags).matcher(String.valueOf(value)).find();
    }

    public static final class Keys {
        public final Map<String, ApiKey> store = new LinkedHashMap<>();
        public final ApiKeyRepository repo = Fake.of(ApiKeyRepository.class, Map.of(
                "findById", a -> Optional.ofNullable(store.get((String) a[0])),
                "findByAppId", a -> store.values().stream().filter(k -> k.getAppId().equals(a[0])).toList(),
                "insert", a -> put((ApiKey) a[0]),
                "deleteByAppId", a -> {
                    long before = store.size();
                    store.values().removeIf(k -> k.getAppId().equals(a[0]));
                    return before - store.size();
                },
                "save", a -> put((ApiKey) a[0])));

        private ApiKey put(ApiKey k) {
            store.put(k.getKey(), k);
            return k;
        }
    }

    public static final class Apps {
        public final Map<String, DeveloperApp> store = new LinkedHashMap<>();
        public final DeveloperAppRepository repo = Fake.of(DeveloperAppRepository.class, Map.ofEntries(
                Map.entry("findById", a -> Optional.ofNullable(store.get((String) a[0]))),
                Map.entry("insert", a -> put((DeveloperApp) a[0])),
                Map.entry("save", a -> put((DeveloperApp) a[0])),
                Map.entry("countByDeveloperIdAndEnvironment", a -> store.values().stream()
                        .filter(x -> x.getDeveloperId().equals(a[0]) && x.getEnvironment() == a[1]).count()),
                Map.entry("countByMerchantIdAndEnvironment", a -> store.values().stream()
                        .filter(x -> x.getMerchantId().equals(a[0]) && x.getEnvironment() == a[1]).count()),
                Map.entry("findByPremisaveWalletId", a -> store.values().stream()
                        .filter(x -> a[0].equals(x.getPremisaveWalletId())).findFirst()),
                Map.entry("findByContextSyncPendingTrue", a -> store.values().stream().filter(DeveloperApp::isContextSyncPending).toList()),
                Map.entry("count", a -> (long) store.size()),
                Map.entry("deleteById", a -> store.remove((String) a[0])),
                Map.entry("countByDeveloperId", a -> store.values().stream().filter(x -> x.getDeveloperId().equals(a[0])).count()),
                Map.entry("countByDeveloperIdAndMerchantId", a -> store.values().stream()
                        .filter(x -> x.getDeveloperId().equals(a[0]) && x.getMerchantId().equals(a[1])).count()),
                Map.entry("countByEnvironment", a -> store.values().stream().filter(x -> x.getEnvironment() == a[0]).count()),
                Map.entry("countByStatus", a -> store.values().stream().filter(x -> x.getStatus() == a[0]).count()),
                Map.entry("adminSearch", a -> TestRepos.page(store.values().stream()
                        .filter(x -> like(x.getDeveloperId(), (String) a[0], 0) && like(x.getMerchantId(), (String) a[1], 0)
                                && like(x.getEnvironment().name(), (String) a[2], 0) && like(x.getStatus().name(), (String) a[3], 0)
                                && like(x.getName(), (String) a[4], java.util.regex.Pattern.CASE_INSENSITIVE))
                        .sorted(java.util.Comparator.comparing(DeveloperApp::getCreatedAt).reversed()).toList(),
                        (org.springframework.data.domain.Pageable) a[5]))));

        private DeveloperApp put(DeveloperApp x) {
            store.put(x.getAppId(), x);
            return x;
        }
    }

    public static final class Transfers {
        public final Map<String, com.premisave.developer.model.TransferRequest> store = new LinkedHashMap<>();
        public final com.premisave.developer.repository.TransferRequestRepository repo = Fake.of(
                com.premisave.developer.repository.TransferRequestRepository.class, Map.of(
                "findById", a -> Optional.ofNullable(store.get((String) a[0])),
                "insert", a -> put((com.premisave.developer.model.TransferRequest) a[0]),
                "save", a -> put((com.premisave.developer.model.TransferRequest) a[0]),
                "findFirst50ByRequesterIdOrToDeveloperIdOrderByCreatedAtDesc", a -> store.values().stream()
                        .filter(t -> t.getRequesterId().equals(a[0]) || a[1].equals(t.getToDeveloperId()))
                        .sorted(java.util.Comparator.comparing(com.premisave.developer.model.TransferRequest::getCreatedAt).reversed())
                        .limit(50).toList(),
                "findByRequesterIdAndKindAndStatus", a -> store.values().stream()
                        .filter(t -> t.getRequesterId().equals(a[0]) && t.getKind() == a[1] && t.getStatus() == a[2]).toList(),
                "findFirstByRequesterIdOrderByCreatedAtDesc", a -> store.values().stream()
                        .filter(t -> t.getRequesterId().equals(a[0]))
                        .max(java.util.Comparator.comparing(com.premisave.developer.model.TransferRequest::getCreatedAt)),
                "countByRequesterIdAndCreatedAtAfter", a -> store.values().stream()
                        .filter(t -> t.getRequesterId().equals(a[0]) && t.getCreatedAt().isAfter((java.time.Instant) a[1])).count(),
                "countByStatus", a -> store.values().stream().filter(t -> t.getStatus() == a[0]).count(),
                "adminSearch", a -> TestRepos.page(store.values().stream()
                        .filter(t -> like(t.getStatus().name(), (String) a[0], 0) && like(t.getKind().name(), (String) a[1], 0)
                                && (like(t.getRequesterId(), (String) a[2], 0) || like(t.getToDeveloperId(), (String) a[2], 0)))
                        .sorted(java.util.Comparator.comparing(com.premisave.developer.model.TransferRequest::getCreatedAt).reversed()).toList(),
                        (org.springframework.data.domain.Pageable) a[3])));

        private com.premisave.developer.model.TransferRequest put(com.premisave.developer.model.TransferRequest t) {
            store.put(t.getId(), t);
            return t;
        }
    }

    public static final class Developers {
        public final Map<String, Developer> store = new LinkedHashMap<>();
        /** Makes the next insert calls fail as if another request had just taken the same unique value. */
        public int duplicateOnInsert;
        /** Runs when an insert fails that way, to model what the other request stored. */
        public Runnable onDuplicate;
        /** Makes save fail the same way. */
        public boolean duplicateOnSave;
        private final Map<String, java.util.function.Function<Object[], Object>> more = Map.of(
                "findByRole", a -> pageOf(store.values().stream().filter(d -> d.getRole() == a[0]), (org.springframework.data.domain.Pageable) a[1]),
                "count", a -> (long) store.size(),
                "countByStatus", a -> store.values().stream().filter(d -> d.getStatus() == a[0]).count(),
                "countByRole", a -> store.values().stream().filter(d -> d.getRole() == a[0]).count(),
                "countByRoleAndStatus", a -> store.values().stream().filter(d -> d.getRole() == a[0] && d.getStatus() == a[1]).count(),
                "deleteById", a -> store.remove((String) a[0]));
        public final DeveloperRepository repo = Fake.of(DeveloperRepository.class, Fake.merge(more, Map.of(
                "findById", a -> Optional.ofNullable(store.get((String) a[0])),
                "findByEmail", a -> store.values().stream().filter(d -> d.getEmail().equals(a[0])).findFirst(),
                "findByUsername", a -> store.values().stream().filter(d -> a[0].equals(d.getUsername())).findFirst(),
                "existsByUsername", a -> store.values().stream().anyMatch(d -> a[0].equals(d.getUsername())),
                "findAll", a -> pageOf(store.values().stream(), (org.springframework.data.domain.Pageable) a[0]),
                "findByStatus", a -> pageOf(store.values().stream().filter(d -> d.getStatus() == a[0]),
                        (org.springframework.data.domain.Pageable) a[1]),
                "search", a -> pageOf(store.values().stream().filter(d -> matches(d, (String) a[0], (String) a[1])),
                        (org.springframework.data.domain.Pageable) a[2]),
                "searchByStatus", a -> pageOf(store.values().stream()
                        .filter(d -> d.getStatus() == a[2] && matches(d, (String) a[0], (String) a[1])),
                        (org.springframework.data.domain.Pageable) a[3]),
                "insert", a -> {
                    if (duplicateOnInsert > 0) {
                        duplicateOnInsert--;
                        if (onDuplicate != null) onDuplicate.run();
                        throw new org.springframework.dao.DuplicateKeyException("duplicate key");
                    }
                    return put((Developer) a[0]);
                },
                "save", a -> {
                    if (duplicateOnSave) throw new org.springframework.dao.DuplicateKeyException("duplicate key");
                    return put((Developer) a[0]);
                })));

        private Developer put(Developer d) {
            store.put(d.getDeveloperId(), d);
            return d;
        }

        /** What the real query does: the pattern anywhere in one of four fields, ignoring case, or the id exactly. */
        private static boolean matches(Developer d, String pattern, String id) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
            return id.equals(d.getDeveloperId())
                    || java.util.stream.Stream.of(d.getEmail(), d.getUsername(), d.getFullName(), d.getCompanyName())
                            .anyMatch(f -> f != null && p.matcher(f).find());
        }

        /** Newest first, cut to the requested page. */
        private static org.springframework.data.domain.Page<Developer> pageOf(java.util.stream.Stream<Developer> all,
                                                                              org.springframework.data.domain.Pageable pageable) {
            java.util.List<Developer> sorted = all.sorted(java.util.Comparator.comparing(Developer::getCreatedAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))).toList();
            int from = Math.min(sorted.size(), pageable.getPageNumber() * pageable.getPageSize());
            int to = Math.min(sorted.size(), from + pageable.getPageSize());
            return new org.springframework.data.domain.PageImpl<>(sorted.subList(from, to), pageable, sorted.size());
        }
    }

    public static final class EmailTokens {
        public final Map<String, com.premisave.developer.model.EmailToken> store = new LinkedHashMap<>();
        public final com.premisave.developer.repository.EmailTokenRepository repo = Fake.of(com.premisave.developer.repository.EmailTokenRepository.class, Map.of(
                "insert", a -> {
                    com.premisave.developer.model.EmailToken x = (com.premisave.developer.model.EmailToken) a[0];
                    store.put(x.getId(), x);
                    return x;
                },
                "findByTokenHash", a -> store.values().stream().filter(x -> x.getTokenHash().equals(a[0])).findFirst(),
                "deleteByTokenHash", a -> {
                    long before = store.size();
                    store.values().removeIf(x -> x.getTokenHash().equals(a[0]));
                    return before - store.size();
                },
                "deleteByDeveloperId", a -> {
                    long before = store.size();
                    store.values().removeIf(x -> x.getDeveloperId().equals(a[0]));
                    return before - store.size();
                },
                "deleteByDeveloperIdAndType", a -> {
                    long before = store.size();
                    store.values().removeIf(x -> x.getDeveloperId().equals(a[0]) && x.getType() == a[1]);
                    return before - store.size();
                },
                "findFirstByDeveloperIdAndTypeOrderByCreatedAtDesc", a -> store.values().stream()
                        .filter(x -> x.getDeveloperId().equals(a[0]) && x.getType() == a[1])
                        .max(java.util.Comparator.comparing(com.premisave.developer.model.EmailToken::getCreatedAt)),
                "countByDeveloperIdAndTypeAndCreatedAtAfter", a -> store.values().stream()
                        .filter(x -> x.getDeveloperId().equals(a[0]) && x.getType() == a[1]
                                && x.getCreatedAt().isAfter((java.time.Instant) a[2])).count()));
    }

    public static final class Revoked {
        public final Map<String, com.premisave.developer.model.RevokedToken> store = new LinkedHashMap<>();
        public final com.premisave.developer.repository.RevokedTokenRepository repo = Fake.of(com.premisave.developer.repository.RevokedTokenRepository.class, Map.of(
                "save", a -> {
                    com.premisave.developer.model.RevokedToken x = (com.premisave.developer.model.RevokedToken) a[0];
                    store.put(x.getTokenId(), x);
                    return x;
                },
                "existsById", a -> store.containsKey((String) a[0])));
    }

    public static final class AdminActions {
        public final java.util.List<com.premisave.developer.model.AdminAction> store = new java.util.ArrayList<>();
        public boolean failToWrite;
        public final com.premisave.developer.repository.AdminActionRepository repo = Fake.of(
                com.premisave.developer.repository.AdminActionRepository.class, Map.of(
                "insert", a -> {
                    if (failToWrite) throw new IllegalStateException("database down");
                    store.add((com.premisave.developer.model.AdminAction) a[0]);
                    return a[0];
                },
                "search", a -> TestRepos.page(store.stream()
                        .filter(x -> like(x.getActorId(), (String) a[0], 0) && like(x.getTargetId(), (String) a[1], 0)
                                && like(x.getAction(), (String) a[2], 0))
                        .sorted(java.util.Comparator.comparing(com.premisave.developer.model.AdminAction::getCreatedAt).reversed()).toList(),
                        (org.springframework.data.domain.Pageable) a[3])));

        /** The actions recorded, oldest first, as "ACTION target". */
        public java.util.List<String> summary() {
            return store.stream().map(x -> x.getAction() + " " + x.getTargetId()).toList();
        }
    }

    public static final class Identities {
        public final Map<String, com.premisave.developer.model.OAuthIdentity> store = new LinkedHashMap<>();
        public final com.premisave.developer.repository.OAuthIdentityRepository repo = Fake.of(com.premisave.developer.repository.OAuthIdentityRepository.class, Map.of(
                "findById", a -> java.util.Optional.ofNullable(store.get((String) a[0])),
                "deleteByDeveloperId", a -> {
                    long before = store.size();
                    store.values().removeIf(x -> x.getDeveloperId().equals(a[0]));
                    return before - store.size();
                },
                "insert", a -> {
                    com.premisave.developer.model.OAuthIdentity x = (com.premisave.developer.model.OAuthIdentity) a[0];
                    if (store.containsKey(x.getId())) throw new org.springframework.dao.DuplicateKeyException("duplicate");
                    store.put(x.getId(), x);
                    return x;
                }));
    }

    /** Remembers every email handed to it. */
    public static final class RecordingMailer implements com.premisave.developer.client.Mailer {
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

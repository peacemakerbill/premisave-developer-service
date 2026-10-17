package com.premisave.developer.service;

import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
import com.premisave.developer.enums.DeveloperRole;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.model.Developer;
import com.premisave.developer.repository.DeveloperRepository;
import com.premisave.developer.security.JwtService;
import com.premisave.developer.security.PasswordHasher;
import com.premisave.developer.service.AdminSeeder.Candidate;
import com.premisave.developer.service.AdminSeeder.Outcome;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two administrators made at startup: both or neither, never twice, never over an existing account. */
class AdminSeederTest {

    private static final Instant T0 = Instant.parse("2026-10-18T08:00:00Z");
    private static final String PASSWORD = "a long password 1";

    private final TestRepos.Developers repo = new TestRepos.Developers();
    private final TestRepos.AdminActions actions = new TestRepos.AdminActions();
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return T0; }
    };
    private final PasswordHasher hasher = new PasswordHasher(10_000);
    private final JwtService jwt = new JwtService(TestProps.JWT_SECRET, "iss", 60, clock);
    private final TestRepos.EmailTokens tokenRepo = new TestRepos.EmailTokens();
    private final TestRepos.RecordingMailer mailer = new TestRepos.RecordingMailer();
    private final MailService mail = new MailService(mailer, Runnable::run, false);
    private final EmailTokenService tokens = new EmailTokenService(tokenRepo.repo, TestProps.mail(), TestProps.PEPPER, clock);
    private final AccountEmailService accountEmail = new AccountEmailService(repo.repo, tokens, mail, hasher, TestProps.mail(), 10, clock);
    private final DeveloperService accounts = new DeveloperService(repo.repo, hasher, jwt, TestProps.fastSecurity(),
            new TestRepos.Revoked().repo, accountEmail, clock);
    private final AdminAudit audit = new AdminAudit(actions.repo, clock);

    private static final Candidate ANN = new Candidate("Ann@Premisave.test", "Ann", "Wambui", PASSWORD);
    private static final Candidate BOB = new Candidate("bob@premisave.test", "Bob", "Otieno", PASSWORD);

    private AdminSeeder seeder(Candidate first, Candidate second) {
        return new AdminSeeder(repo.repo, accounts, accountEmail, hasher, audit, 10, clock, first, second);
    }

    private Developer existing(String id, String email, DeveloperRole role) {
        Developer d = new Developer();
        d.setDeveloperId(id);
        d.setEmail(email);
        d.setRole(role);
        d.setStatus(DeveloperStatus.ACTIVE);
        repo.store.put(id, d);
        return d;
    }

    @Test
    void bothAdministratorsAreCreatedActiveConfirmedAndAbleToSignIn() {
        AdminSeeder.Result r = seeder(ANN, BOB).seed();
        assertEquals(Outcome.CREATED, r.outcome());
        assertEquals(2, repo.store.size());
        for (Developer d : repo.store.values()) {
            assertEquals(DeveloperRole.ADMIN, d.getRole());
            assertEquals(DeveloperStatus.ACTIVE, d.getStatus());
            assertTrue(d.isEmailVerified());
            assertTrue(d.getDeveloperId().startsWith("dev_"));
            assertNotNull(d.getUsername());
            assertNotNull(d.getTermsAcceptedAt());
            assertTrue(hasher.verify(PASSWORD, d.getPasswordHash()));
            assertFalse(d.getPasswordHash().contains(PASSWORD));
        }
        Developer ann = repo.repo.findByEmail("ann@premisave.test").orElseThrow();
        assertEquals("Ann Wambui", ann.getFullName());
        assertTrue(repo.repo.findByEmail("bob@premisave.test").isPresent());
        assertFalse(r.message().contains(PASSWORD));
    }

    @Test
    void eachNewAdministratorIsEmailedThatTheAccountExistsWithoutAPassword() {
        AdminSeeder.Result r = seeder(ANN, BOB).seed();
        assertEquals(2, mailer.sent.size());
        assertEquals(List.of("ann@premisave.test", "bob@premisave.test"), mailer.sent.stream().map(m -> m.to()).sorted().toList());
        for (TestRepos.RecordingMailer.Sent m : mailer.sent) {
            assertEquals("You are now a Premisave administrator", m.subject());
            assertTrue(m.html().contains("Administrator"));
            assertTrue(m.html().contains(m.to()));
            assertFalse(m.html().contains(PASSWORD));
        }
        assertTrue(mailer.sent.stream().anyMatch(m -> m.html().contains("Hello Ann,")));
        assertTrue(mailer.sent.stream().anyMatch(m -> m.html().contains("Hello Bob,")));
        assertTrue(r.message().contains("An email was sent to each"));
    }

    @Test
    void nothingIsEmailedWhenTheSeedIsSkippedOrInvalid() {
        existing("dev_x", "someone@else.test", DeveloperRole.ADMIN);
        seeder(ANN, BOB).seed();
        seeder(new Candidate("", "", "", ""), BOB).seed();
        assertTrue(mailer.sent.isEmpty());
    }

    @Test
    void aMailServerThatIsDownDoesNotStopTheAccountsFromBeingCreated() {
        mailer.fail = true;
        assertEquals(Outcome.CREATED, seeder(ANN, BOB).seed().outcome());
        assertEquals(2, repo.store.size());
    }

    @Test
    void withNoMailServerTheLogSaysNoEmailWasSent() {
        MailService off = new MailService(null, Runnable::run, false);
        AccountEmailService quiet = new AccountEmailService(repo.repo, tokens, off, hasher, TestProps.mail(), 10, clock);
        AdminSeeder.Result r = new AdminSeeder(repo.repo, accounts, quiet, hasher, audit, 10, clock, ANN, BOB).seed();
        assertEquals(Outcome.CREATED, r.outcome());
        assertTrue(r.message().contains("Mail is off"));
    }

    @Test
    void theCreationIsWrittenToTheAuditTrail() {
        seeder(ANN, BOB).seed();
        assertEquals(2, actions.store.size());
        assertTrue(actions.store.stream().allMatch(a -> a.getActorId().equals("startup-seed") && a.getAction().equals("DEVELOPER_CREATED")));
    }

    @Test
    void aSecondStartupCreatesNothingMore() {
        AdminSeeder s = seeder(ANN, BOB);
        assertEquals(Outcome.CREATED, s.seed().outcome());
        AdminSeeder.Result again = s.seed();
        assertEquals(Outcome.ALREADY_THERE, again.outcome());
        assertEquals(2, repo.store.size());
        assertEquals(2, actions.store.size());
    }

    @Test
    void anExistingAdministratorOfAnyKindStopsTheSeed() {
        existing("dev_x", "someone@else.test", DeveloperRole.ADMIN);
        AdminSeeder.Result r = seeder(ANN, BOB).seed();
        assertEquals(Outcome.ALREADY_THERE, r.outcome());
        assertEquals(1, repo.store.size());
        assertTrue(r.message().contains("1 administrator already exists"));
    }

    @Test
    void onceAnAdministratorExistsEmptySettingsAreNotAnError() {
        existing("dev_x", "someone@else.test", DeveloperRole.ADMIN);
        AdminSeeder.Result r = seeder(new Candidate("", "", "", ""), new Candidate(null, null, null, null)).seed();
        assertEquals(Outcome.ALREADY_THERE, r.outcome());
        assertEquals(1, repo.store.size());
    }

    @Test
    void anAddressAlreadyInUseStopsTheSeedBeforeAnythingIsCreated() {
        existing("dev_x", "bob@premisave.test", DeveloperRole.DEVELOPER);
        AdminSeeder.Result r = seeder(ANN, BOB).seed();
        assertEquals(Outcome.ALREADY_THERE, r.outcome());
        assertEquals(1, repo.store.size());
        assertEquals(DeveloperRole.DEVELOPER, repo.store.get("dev_x").getRole());
        assertTrue(repo.repo.findByEmail("ann@premisave.test").isEmpty());
    }

    @Test
    void aSettingThatIsNotValidCreatesNothing() {
        assertEquals(Outcome.INVALID, seeder(ANN, new Candidate("", "Bob", "Otieno", PASSWORD)).seed().outcome());
        assertEquals(Outcome.INVALID, seeder(ANN, new Candidate("not-an-email", "Bob", "Otieno", PASSWORD)).seed().outcome());
        assertEquals(Outcome.INVALID, seeder(ANN, new Candidate("bob@premisave.test", "", "Otieno", PASSWORD)).seed().outcome());
        assertEquals(Outcome.INVALID, seeder(ANN, new Candidate("bob@premisave.test", "Bob", "", PASSWORD)).seed().outcome());
        assertEquals(Outcome.INVALID, seeder(ANN, new Candidate("bob@premisave.test", "Bob", "Otieno", "short")).seed().outcome());
        assertEquals(Outcome.INVALID, seeder(ANN, new Candidate("bob@premisave.test", "Bob", "Otieno", null)).seed().outcome());
        assertEquals(Outcome.INVALID, seeder(new Candidate(null, null, null, null), BOB).seed().outcome());
        assertTrue(repo.store.isEmpty());
    }

    @Test
    void theErrorNamesTheSettingsToFixAndNeverTheirValues() {
        AdminSeeder.Result r = seeder(ANN, new Candidate("bob@premisave.test", "Bob", "Otieno", "short")).seed();
        assertTrue(r.message().contains("DEVELOPER_SEED_ADMIN2_"));
        assertFalse(r.message().contains("short\""));
    }

    @Test
    void twoAdministratorsCannotShareAnAddress() {
        AdminSeeder.Result r = seeder(ANN, new Candidate(" ANN@premisave.test ", "Ann", "Two", PASSWORD)).seed();
        assertEquals(Outcome.INVALID, r.outcome());
        assertTrue(repo.store.isEmpty());
    }

    @Test
    void ifTheSecondAccountLosesARaceTheFirstIsTakenBackAndNothingIsKept() {
        int[] inserts = {0};
        DeveloperRepository racing = (DeveloperRepository) Proxy.newProxyInstance(DeveloperRepository.class.getClassLoader(),
                new Class<?>[] {DeveloperRepository.class}, (proxy, m, args) -> {
                    if (m.getName().equals("insert") && ++inserts[0] == 2) throw new DuplicateKeyException("duplicate key");
                    try {
                        return m.invoke(repo.repo, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        AdminSeeder.Result r = new AdminSeeder(racing, accounts, accountEmail, hasher, audit, 10, clock, ANN, BOB).seed();
        assertEquals(Outcome.ALREADY_THERE, r.outcome());
        assertTrue(repo.store.isEmpty());
        assertTrue(actions.store.isEmpty());
    }

    @Test
    void theRunnerNeverLetsAFailureStopTheService() {
        AdminSeeder broken = new AdminSeeder(null, accounts, accountEmail, hasher, audit, 10, clock, ANN, BOB);
        broken.run(null);
        assertTrue(repo.store.isEmpty());
    }

    @Test
    void thePasswordsOfTheNewAdministratorsDifferFromEachOtherInTheStore() {
        seeder(ANN, BOB).seed();
        List<String> hashes = repo.store.values().stream().map(Developer::getPasswordHash).toList();
        assertFalse(hashes.get(0).equals(hashes.get(1)));
    }
}
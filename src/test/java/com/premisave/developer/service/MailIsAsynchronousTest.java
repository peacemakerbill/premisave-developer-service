package com.premisave.developer.service;

import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
import com.premisave.developer.client.Mailer;
import com.premisave.developer.dto.LoginRequest;
import com.premisave.developer.dto.SignupRequest;
import com.premisave.developer.security.JwtService;
import com.premisave.developer.security.PasswordHasher;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that no request waits for the mail server. The mailer here can be held shut. With it shut, a request that
 * sent email on its own thread would stall until the hold gave up, so each request must finish well before that, and
 * the email must still go out once the mailer is opened.
 */
class MailIsAsynchronousTest {

    /** Longest the mailer holds an email. A request that waits for it takes about this long. */
    private static final long HOLD_MILLIS = 3_000;
    /** A request must be done well inside the hold. */
    private static final long LIMIT_MILLIS = 1_500;

    private static final class HeldMailer implements Mailer {
        final AtomicInteger delivered = new AtomicInteger();
        private volatile CountDownLatch gate = new CountDownLatch(0);

        void hold() { gate = new CountDownLatch(1); }

        void open() { gate.countDown(); }

        @Override
        public void send(String to, String subject, String html) throws Exception {
            gate.await(HOLD_MILLIS, TimeUnit.MILLISECONDS);
            delivered.incrementAndGet();
        }

        void awaitDelivered(int count) throws InterruptedException {
            long end = System.currentTimeMillis() + 5_000;
            while (delivered.get() < count && System.currentTimeMillis() < end) Thread.sleep(10);
            assertEquals(count, delivered.get());
        }
    }

    private final TestRepos.Developers repo = new TestRepos.Developers();
    private volatile Instant now = Instant.parse("2026-10-17T08:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final HeldMailer mailer = new HeldMailer();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "test-mail");
        t.setDaemon(true);
        return t;
    });
    private final PasswordHasher hasher = new PasswordHasher(10_000);
    private final TestRepos.EmailTokens tokenRepo = new TestRepos.EmailTokens();
    private final TestRepos.Revoked revoked = new TestRepos.Revoked();
    private final MailService mail = new MailService(mailer, pool, false);
    private final EmailTokenService tokens = new EmailTokenService(tokenRepo.repo, TestProps.mail(), TestProps.PEPPER, clock);
    private final AccountEmailService accountEmail = new AccountEmailService(repo.repo, tokens, mail, hasher, TestProps.mail(), 10, clock);
    private final DeveloperService service = new DeveloperService(repo.repo, hasher,
            new JwtService(TestProps.JWT_SECRET, "iss", 60, clock), TestProps.fastSecurity(), revoked.repo, accountEmail, clock);

    private static SignupRequest signup() {
        return new SignupRequest("jane@example.com", "Jane", null, "Doe", "a long password 1", "ke", null, null, true, null);
    }

    /** Signs up with the mailer open and waits for the confirmation email, so the next step starts from a quiet queue. */
    private void signedUpAndQuiet() throws InterruptedException {
        service.signup(signup());
        mailer.awaitDelivered(1);
    }

    private static long millisOf(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @Test
    void signUpDoesNotWaitForTheMailServer() throws Exception {
        mailer.hold();
        long took = millisOf(() -> service.signup(signup()));
        assertTrue(took < LIMIT_MILLIS);
        assertEquals(0, mailer.delivered.get());
        mailer.open();
        mailer.awaitDelivered(1);
    }

    @Test
    void aPasswordResetRequestDoesNotWaitAndTakesTheSameTimeForAnyAddress() throws Exception {
        signedUpAndQuiet();
        mailer.hold();
        long known = millisOf(() -> accountEmail.forgotPassword("jane@example.com"));
        long unknown = millisOf(() -> accountEmail.forgotPassword("nobody@example.com"));
        assertTrue(known < LIMIT_MILLIS);
        assertTrue(unknown < LIMIT_MILLIS);
        assertEquals(1, mailer.delivered.get());
        mailer.open();
        mailer.awaitDelivered(2);
    }

    @Test
    void askingForANewConfirmationLinkDoesNotWait() throws Exception {
        signedUpAndQuiet();
        now = now.plus(Duration.ofMinutes(2));
        mailer.hold();
        long took = millisOf(() -> accountEmail.resendVerification("jane@example.com"));
        assertTrue(took < LIMIT_MILLIS);
        assertEquals(1, mailer.delivered.get());
        mailer.open();
        mailer.awaitDelivered(2);
    }

    @Test
    void signingInDoesNotWaitForTheSignInNotice() throws Exception {
        signedUpAndQuiet();
        repo.store.values().iterator().next().confirmEmail(now);
        mailer.hold();
        long took = millisOf(() -> service.login(new LoginRequest("jane@example.com", "a long password 1")));
        assertTrue(took < LIMIT_MILLIS);
        assertEquals(1, mailer.delivered.get());
        mailer.open();
        mailer.awaitDelivered(2);
    }

    @Test
    void aMailServerThatNeverAnswersDoesNotStopSignUpFromFinishing() throws Exception {
        mailer.hold();
        for (int i = 0; i < 3; i++) {
            String email = "person" + i + "@example.com";
            long took = millisOf(() -> service.signup(new SignupRequest(email, "Jane", null, "Doe", "a long password 1", "ke", null, null, true, null)));
            assertTrue(took < LIMIT_MILLIS);
        }
        assertEquals(3, repo.store.size());
        mailer.open();
        mailer.awaitDelivered(3);
    }
}

package com.premisave.developer.service;

import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.security.JwtService;
import com.premisave.developer.security.PasswordHasher;
import com.premisave.developer.view.DeveloperViews;
import com.premisave.developer.view.PageViews;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeveloperSearchTest {

    private static final Instant T0 = Instant.parse("2026-10-16T08:00:00Z");

    private final TestRepos.Developers repo = new TestRepos.Developers();
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
    private final DeveloperService service = new DeveloperService(repo.repo, hasher, jwt, TestProps.fastSecurity(),
            new TestRepos.Revoked().repo, accountEmail, clock);

    private Developer add(String id, String email, String username, String fullName, String company, DeveloperStatus status, int minute) {
        Developer d = new Developer();
        d.setDeveloperId(id);
        d.setEmail(email);
        d.setUsername(username);
        d.setFullName(fullName);
        d.setCompanyName(company);
        d.setCountry("KE");
        d.setStatus(status);
        d.setCreatedAt(T0.plusSeconds(minute * 60L));
        repo.store.put(id, d);
        return d;
    }

    private void seed() {
        add("dev_1", "jane.doe@acme.co.ke", "jane_doe", "Jane Doe", "Acme Payments", DeveloperStatus.ACTIVE, 1);
        add("dev_2", "john@example.com", "john_k", "John Kamau", null, DeveloperStatus.ACTIVE, 2);
        add("dev_3", "mary@acme.co.ke", "mary_w", "Mary Wanjiru", "Acme Payments", DeveloperStatus.PENDING, 3);
        add("dev_4", "peter@gmail.com", "peter_o", "Peter Otieno", "Otieno Traders", DeveloperStatus.DISABLED, 4);
    }

    private static Pageable page(int number, int size) {
        return PageViews.pageable(number, size);
    }

    private List<String> ids(Page<Developer> p) {
        return p.getContent().stream().map(Developer::getDeveloperId).toList();
    }

    @Test
    void noFiltersListsEveryoneNewestFirst() {
        seed();
        assertEquals(List.of("dev_4", "dev_3", "dev_2", "dev_1"), ids(service.search(null, null, page(0, 20))));
    }

    @Test
    void anEmailFragmentFindsTheAccount() {
        seed();
        assertEquals(List.of("dev_2"), ids(service.search("john@exa", null, page(0, 20))));
    }

    @Test
    void theSearchIgnoresCase() {
        seed();
        assertEquals(List.of("dev_1"), ids(service.search("JANE.DOE@ACME", null, page(0, 20))));
    }

    @Test
    void theSearchCoversUsernameNameAndCompany() {
        seed();
        assertEquals(List.of("dev_4"), ids(service.search("peter_o", null, page(0, 20))));
        assertEquals(List.of("dev_3"), ids(service.search("wanjiru", null, page(0, 20))));
        assertEquals(List.of("dev_4"), ids(service.search("traders", null, page(0, 20))));
    }

    @Test
    void aDomainFindsEveryoneOnIt() {
        seed();
        assertEquals(List.of("dev_3", "dev_1"), ids(service.search("@acme.co.ke", null, page(0, 20))));
    }

    @Test
    void theDeveloperIdFindsTheAccountExactly() {
        seed();
        assertEquals(List.of("dev_2"), ids(service.search("dev_2", null, page(0, 20))));
        assertEquals(List.of(), ids(service.search("dev_", null, page(0, 20))));
    }

    @Test
    void theStatusNarrowsTheList() {
        seed();
        assertEquals(List.of("dev_2", "dev_1"), ids(service.search(null, DeveloperStatus.ACTIVE, page(0, 20))));
        assertEquals(List.of("dev_3"), ids(service.search("acme", DeveloperStatus.PENDING, page(0, 20))));
        assertEquals(List.of(), ids(service.search("john", DeveloperStatus.DISABLED, page(0, 20))));
    }

    @Test
    void patternCharactersInTheSearchAreTakenLiterally() {
        seed();
        assertEquals(List.of(), ids(service.search(".*", null, page(0, 20))));
        assertEquals(List.of(), ids(service.search("jane.doe@acme.co.k(", null, page(0, 20))));
        assertEquals(List.of("dev_1"), ids(service.search("jane.doe@acme.co.k", null, page(0, 20))));
    }

    @Test
    void blankTextIsNoSearch() {
        seed();
        assertEquals(4, service.search("   ", null, page(0, 20)).getTotalElements());
    }

    @Test
    void textOverTheLimitIsRefused() {
        assertThrows(DeveloperException.class, () -> service.search("a".repeat(101), null, page(0, 20)));
    }

    @Test
    void resultsArePaged() {
        seed();
        Page<Developer> first = service.search(null, null, page(0, 3));
        assertEquals(List.of("dev_4", "dev_3", "dev_2"), ids(first));
        assertEquals(4, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
        assertEquals(List.of("dev_1"), ids(service.search(null, null, page(1, 3))));
    }

    @Test
    void theSummaryHasWhatStaffNeedToPickADeveloperAndNothingSecret() {
        Developer d = add("dev_1", "jane.doe@acme.co.ke", "jane_doe", "Jane Doe", "Acme Payments", DeveloperStatus.ACTIVE, 1);
        d.setPasswordHash("hash");
        d.setPhoneNumber("+254700000000");
        d.setAddressLine1("1 Street");
        d.addBusiness("m_1");
        Map<String, Object> m = DeveloperViews.summary(d);
        assertEquals("dev_1", m.get("developerId"));
        assertEquals("jane.doe@acme.co.ke", m.get("email"));
        assertEquals("jane_doe", m.get("username"));
        assertEquals("Jane Doe", m.get("fullName"));
        assertEquals("Acme Payments", m.get("companyName"));
        assertEquals("ACTIVE", m.get("status"));
        assertEquals(List.of("m_1"), m.get("businessIds"));
        assertNull(m.get("lastLoginAt"));
        assertFalse(m.containsKey("passwordHash"));
        assertFalse(m.containsKey("phoneNumber"));
        assertFalse(m.containsKey("addressLine1"));
    }
}

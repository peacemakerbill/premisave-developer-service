package com.premisave.developer.service;

import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.DeveloperRole;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.repository.DeveloperRepository;
import com.premisave.developer.security.PasswordHasher;
import com.premisave.developer.util.Ids;
import com.premisave.developer.util.PasswordPolicy;
import com.premisave.developer.util.PersonNames;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates the first two administrators when the service starts. It is off unless developer.seed-admins.enabled is true.
 *
 * It creates both accounts or neither. If any administrator already exists, or either address is already used by an
 * account, it stops at once and changes nothing. The check for an existing administrator comes first, so the settings can be
 * emptied once the accounts exist, so a restart never creates the accounts a second time and never touches
 * an account that is already there. A setting that is missing or not valid is logged and nothing is created. Neither
 * outcome stops the service from starting.
 *
 * The accounts are active with a confirmed address and the password given in the settings. The password is hashed
 * before it is stored and is never written to the log.
 */
@Component("developerAdminSeeder")
@ConditionalOnProperty(name = "developer.seed-admins.enabled", havingValue = "true")
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    /** The actor written to the audit trail for accounts made here. */
    static final AdminActor STARTUP = new AdminActor("startup-seed", null);

    public enum Outcome { CREATED, ALREADY_THERE, INVALID }

    public record Result(Outcome outcome, String message) {}

    /** One account to create, as taken from the settings. */
    public record Candidate(String email, String firstName, String lastName, String password) {}

    private final DeveloperRepository developers;
    private final DeveloperService accounts;
    private final PasswordHasher hasher;
    private final AdminAudit audit;
    private final int passwordMinLength;
    private final Clock clock;
    private final Candidate first;
    private final Candidate second;

    @Autowired
    public AdminSeeder(DeveloperRepository developers, DeveloperService accounts, PasswordHasher hasher, AdminAudit audit,
                       DeveloperProperties props,
                       @Value("${developer.seed-admins.first.email:}") String firstEmail,
                       @Value("${developer.seed-admins.first.first-name:}") String firstFirstName,
                       @Value("${developer.seed-admins.first.last-name:}") String firstLastName,
                       @Value("${developer.seed-admins.first.password:}") String firstPassword,
                       @Value("${developer.seed-admins.second.email:}") String secondEmail,
                       @Value("${developer.seed-admins.second.first-name:}") String secondFirstName,
                       @Value("${developer.seed-admins.second.last-name:}") String secondLastName,
                       @Value("${developer.seed-admins.second.password:}") String secondPassword) {
        this(developers, accounts, hasher, audit, props.security().passwordMinLength(), Clock.systemUTC(),
                new Candidate(firstEmail, firstFirstName, firstLastName, firstPassword),
                new Candidate(secondEmail, secondFirstName, secondLastName, secondPassword));
    }

    public AdminSeeder(DeveloperRepository developers, DeveloperService accounts, PasswordHasher hasher, AdminAudit audit,
                       int passwordMinLength, Clock clock, Candidate first, Candidate second) {
        this.developers = developers;
        this.accounts = accounts;
        this.hasher = hasher;
        this.audit = audit;
        this.passwordMinLength = passwordMinLength;
        this.clock = clock;
        this.first = first;
        this.second = second;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (RuntimeException e) {
            log.error("Admin seed: stopped by an unexpected error, nothing more was created: {}", e.toString());
        }
    }

    /** Creates both accounts, or none, and logs what happened. */
    public Result seed() {
        // First, so that once the accounts exist the settings can be emptied without the seed complaining at every start.
        long admins = developers.countByRole(DeveloperRole.ADMIN);
        if (admins > 0) {
            return report(Outcome.ALREADY_THERE, "Admin seed: skipped. " + admins + " administrator"
                    + (admins == 1 ? " already exists" : "s already exist") + ", so nothing was created.");
        }

        List<Candidate> wanted = new ArrayList<>(2);
        String problem = check(wanted);
        if (problem != null) return report(Outcome.INVALID, "Admin seed: nothing created. " + problem);
        for (Candidate c : wanted) {
            if (developers.findByEmail(c.email()).isPresent()) {
                return report(Outcome.ALREADY_THERE, "Admin seed: skipped. An account with the address " + c.email()
                        + " already exists, so nothing was created.");
            }
        }

        List<Developer> made = new ArrayList<>(2);
        for (Candidate c : wanted) {
            Developer d = build(c);
            try {
                developers.insert(d);
            } catch (DuplicateKeyException raced) {
                made.forEach(m -> developers.deleteById(m.getDeveloperId()));
                return report(Outcome.ALREADY_THERE, "Admin seed: skipped. Another instance created an account with the address "
                        + c.email() + " at the same moment, so nothing was kept.");
            }
            made.add(d);
        }
        for (Developer d : made) {
            audit.record(STARTUP, "DEVELOPER_CREATED", "DEVELOPER", d.getDeveloperId(), "role ADMIN, created at startup");
        }
        return report(Outcome.CREATED, "Admin seed: created 2 administrators, " + wanted.get(0).email() + " and "
                + wanted.get(1).email() + ". Sign in and change the passwords.");
    }

    /** Fills wanted with the cleaned candidates, or returns what is wrong with the settings. */
    private String check(List<Candidate> wanted) {
        Candidate[] given = {first, second};
        String[] label = {"first", "second"};
        for (int i = 0; i < 2; i++) {
            Candidate c = given[i];
            try {
                String email = DeveloperService.normaliseEmail(c.email());
                String firstName = PersonNames.required("firstName", c.firstName(), 60);
                String lastName = PersonNames.required("lastName", c.lastName(), 60);
                PasswordPolicy.check(passwordMinLength, c.password(), email);
                wanted.add(new Candidate(email, firstName, lastName, c.password()));
            } catch (DeveloperException e) {
                return "The " + label[i] + " administrator is not valid: " + e.getMessage()
                        + ". Check the DEVELOPER_SEED_ADMIN" + (i + 1) + "_ settings.";
            }
        }
        if (wanted.get(0).email().equals(wanted.get(1).email())) {
            return "The two administrators need different email addresses.";
        }
        return null;
    }

    private Developer build(Candidate c) {
        Instant now = clock.instant();
        Developer d = new Developer();
        d.setDeveloperId(Ids.next("dev"));
        d.setEmail(c.email());
        d.setFirstName(c.firstName());
        d.setLastName(c.lastName());
        d.setFullName(PersonNames.full(c.firstName(), null, c.lastName()));
        d.setUsername(accounts.generateUsername(c.firstName(), c.lastName(), c.email()));
        d.setPasswordHash(hasher.hash(c.password()));
        d.setRole(DeveloperRole.ADMIN);
        d.setStatus(DeveloperStatus.ACTIVE);
        d.confirmEmail(now);
        d.setTermsAcceptedAt(now);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        return d;
    }

    private Result report(Outcome outcome, String message) {
        if (outcome == Outcome.INVALID) log.error(message);
        else log.info(message);
        return new Result(outcome, message);
    }
}
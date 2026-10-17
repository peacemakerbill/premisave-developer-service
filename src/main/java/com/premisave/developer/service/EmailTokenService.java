package com.premisave.developer.service;

import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.EmailTokenType;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.EmailToken;
import com.premisave.developer.repository.EmailTokenRepository;
import com.premisave.developer.util.Hashing;
import com.premisave.developer.util.Ids;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Issues and redeems the single use links sent by email. A token is 32 random bytes, handed out once and stored only as
 * an HMAC under the API key pepper. A link is redeemed by deleting its row, so two requests with the same link cannot
 * both succeed. Issuing is throttled per developer and type, which also limits how much email anyone can cause.
 */
@Service("developerEmailTokenService")
public class EmailTokenService {

    /** A new token and the address it is for. */
    public record Issued(String token, EmailToken row) {}

    private final EmailTokenRepository tokens;
    private final DeveloperProperties.Mail mail;
    private final String pepper;
    private final Clock clock;

    @Autowired
    public EmailTokenService(EmailTokenRepository tokens, DeveloperProperties props) {
        this(tokens, props.mail(), props.keys().pepper(), Clock.systemUTC());
    }

    public EmailTokenService(EmailTokenRepository tokens, DeveloperProperties.Mail mail, String pepper, Clock clock) {
        this.tokens = tokens;
        this.mail = mail;
        this.pepper = pepper;
        this.clock = clock;
    }

    /**
     * Says how many seconds the developer must wait before another email of this kind, or empty when one may be sent.
     * The wait comes from the resend cooldown, or from the hourly cap when it is used up.
     */
    public Optional<Long> waitSeconds(Developer d, EmailTokenType type) {
        Instant now = clock.instant();
        Optional<EmailToken> last = tokens.findFirstByDeveloperIdAndTypeOrderByCreatedAtDesc(d.getDeveloperId(), type);
        if (last.isPresent() && mail.resendCooldownSeconds() > 0) {
            long millis = Duration.between(now, last.get().getCreatedAt().plusSeconds(mail.resendCooldownSeconds())).toMillis();
            if (millis > 0) return Optional.of((millis + 999) / 1000);
        }
        long lastHour = tokens.countByDeveloperIdAndTypeAndCreatedAtAfter(d.getDeveloperId(), type, now.minus(Duration.ofHours(1)));
        if (lastHour >= mail.maxPerHour()) return Optional.of(Duration.ofMinutes(10).toSeconds());
        return Optional.empty();
    }

    public Issued issue(Developer d, EmailTokenType type) {
        Instant now = clock.instant();
        String raw = Ids.randomToken(32);
        EmailToken row = new EmailToken();
        row.setId(Ids.next("etk"));
        row.setTokenHash(hash(raw));
        row.setDeveloperId(d.getDeveloperId());
        row.setType(type);
        row.setEmail(d.getEmail());
        row.setCreatedAt(now);
        row.setExpiresAt(type == EmailTokenType.VERIFY_EMAIL
                ? now.plus(Duration.ofHours(mail.verificationTtlHours()))
                : now.plus(Duration.ofMinutes(mail.resetTtlMinutes())));
        tokens.insert(row);
        return new Issued(raw, row);
    }

    /** Uses the token up. Throws INVALID_TOKEN when it is unknown, expired, of another kind or already used. */
    public EmailToken redeem(String raw, EmailTokenType type) {
        if (raw == null || raw.isBlank() || raw.length() > 200) throw invalid();
        String hash = hash(raw.trim());
        EmailToken row = tokens.findByTokenHash(hash).orElseThrow(EmailTokenService::invalid);
        if (row.getType() != type || !clock.instant().isBefore(row.getExpiresAt())) throw invalid();
        if (tokens.deleteByTokenHash(hash) != 1) throw invalid();
        return row;
    }

    /** Ends every reset link of the developer, for example after the password changed. */
    public void clear(String developerId, EmailTokenType type) {
        tokens.deleteByDeveloperIdAndType(developerId, type);
    }

    String hash(String raw) {
        return Hashing.hmacSha256Hex(pepper, "email-token:" + raw);
    }

    private static DeveloperException invalid() {
        return new DeveloperException(ErrorCode.INVALID_TOKEN, "This link is invalid or has expired. Ask for a new one.");
    }
}

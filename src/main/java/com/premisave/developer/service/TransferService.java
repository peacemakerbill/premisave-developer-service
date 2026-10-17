package com.premisave.developer.service;

import com.premisave.developer.client.WalletApi;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.dto.MerchantInfo;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.TransferKind;
import com.premisave.developer.enums.TransferStatus;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.DeveloperApp;
import com.premisave.developer.model.TransferRequest;
import com.premisave.developer.repository.DeveloperAppRepository;
import com.premisave.developer.repository.DeveloperRepository;
import com.premisave.developer.repository.TransferRequestRepository;
import com.premisave.developer.util.EmailTemplates;
import com.premisave.developer.util.Hashing;
import com.premisave.developer.util.Ids;
import com.premisave.developer.util.MaskedEmail;
import com.premisave.developer.view.TransferViews;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Moving a production app to another developer account, and taking up an approved business by its PRM merchant number.
 *
 * <p>A business is applied for once, by one account. Another account can go live with the same merchant number only when the
 * owner agrees, and the owner shows that by sharing a six digit code. The code is emailed to the address linked to the
 * business, which is the wallet account named on the application, or the business's contact address when no wallet is
 * linked. The requester never sees the code, so it has to come from the owner.
 *
 * <p>An app transfer moves the app and gives the other account use of the business, so it can go live with the same
 * merchant number. The business stays with the account that applied for it, together with its other apps. The keys of a
 * moved app are revoked, so the new owner makes their own.
 *
 * <p>The code is stored only as an HMAC under the API key pepper, lasts a few minutes, works once, and a few wrong tries
 * close the request. Asking for codes is throttled per account, and so is guessing merchant numbers.
 */
@Service("developerTransferService")
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The timing and the limits. codeMinutes is how long a code works, perHour caps requests and wrong merchant numbers. */
    public record Rules(int codeMinutes, int cooldownSeconds, int perHour, int keepDays) {
        public static final Rules DEFAULT = new Rules(15, 60, 10, 7);
    }

    private final TransferRequestRepository transfers;
    private final DeveloperRepository developers;
    private final DeveloperAppRepository apps;
    private final AppService appService;
    private final BusinessService businesses;
    private final WalletApi wallet;
    private final ApiKeyService keys;
    private final AppContextSync sync;
    private final MailService mail;
    private final String pepper;
    private final String portalUrl;
    private final Rules rules;
    private final Supplier<String> codes;
    private final Clock clock;

    @Autowired
    public TransferService(TransferRequestRepository transfers, DeveloperRepository developers, DeveloperAppRepository apps,
                           AppService appService, BusinessService businesses, WalletApi wallet, ApiKeyService keys,
                           AppContextSync sync, MailService mail, DeveloperProperties props) {
        this(transfers, developers, apps, appService, businesses, wallet, keys, sync, mail, props.keys().pepper(),
                props.mail().portalBaseUrl(), Rules.DEFAULT, TransferService::randomCode, Clock.systemUTC());
    }

    public TransferService(TransferRequestRepository transfers, DeveloperRepository developers, DeveloperAppRepository apps,
                           AppService appService, BusinessService businesses, WalletApi wallet, ApiKeyService keys,
                           AppContextSync sync, MailService mail, String pepper, String portalUrl, Rules rules,
                           Supplier<String> codes, Clock clock) {
        this.transfers = transfers;
        this.developers = developers;
        this.apps = apps;
        this.appService = appService;
        this.businesses = businesses;
        this.wallet = wallet;
        this.keys = keys;
        this.sync = sync;
        this.mail = mail;
        this.pepper = pepper;
        this.portalUrl = portalUrl;
        this.rules = rules;
        this.codes = codes;
        this.clock = clock;
    }

    // Starting

    /** Asks to move a production app to another account. Emails the code to the address linked to the business. */
    public Map<String, Object> startAppTransfer(Developer from, String appId, String toEmail) {
        DeveloperApp app = appService.get(from, appId);
        if (app.getEnvironment() != Environment.LIVE) {
            throw DeveloperException.invalidState("Only a production app can be transferred. Create the app again on the other account for sandbox");
        }
        String target = toEmail == null ? "" : toEmail.trim().toLowerCase(Locale.ROOT);
        if (target.equals(from.getEmail().toLowerCase(Locale.ROOT))) {
            throw DeveloperException.validation("toEmail", "is your own account");
        }
        Developer to = developers.findByEmail(target).filter(d -> d.getStatus() == DeveloperStatus.ACTIVE)
                .orElseThrow(() -> DeveloperException.validation("toEmail", "no active developer account uses this address"));
        businesses.requireRoomFor(to, app.getMerchantId());

        Instant now = clock.instant();
        throttle(from, now);
        Map<String, Object> merchant = wallet.getMerchantView(app.getMerchantId());
        String sendTo = confirmationAddress(merchant, app.getPremisaveWalletAccount());

        TransferRequest t = blank(TransferKind.APP_TRANSFER, from, now);
        t.setToDeveloperId(to.getDeveloperId());
        t.setFromEmail(from.getEmail());
        t.setToEmail(to.getEmail());
        t.setAppId(app.getAppId());
        t.setAppName(app.getName());
        describe(t, merchant);
        cancelOlder(from, TransferKind.APP_TRANSFER, app.getAppId());
        String code = arm(t, sendTo, now);
        transfers.insert(t);
        sendCode(t, from, sendTo, code,
                "move the production app \"" + app.getName() + "\" to another developer account (" + MaskedEmail.of(to.getEmail()) + ")");
        return TransferViews.view(t, from.getDeveloperId(), now);
    }

    /** Asks to use an approved business that another account applied for. Same code, same address, no app moves. */
    public Map<String, Object> startClaim(Developer requester, String merchantNumber) {
        Instant now = clock.instant();
        throttle(requester, now);
        String number = merchantNumber == null ? "" : merchantNumber.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> merchant = findApproved(requester, number, now);
        MerchantInfo info = MerchantInfo.from(merchant);
        if (requester.ownsBusiness(info.merchantId())) {
            throw new DeveloperException(ErrorCode.BUSINESS_EXISTS, "This business is already on your account")
                    .with("businessId", info.merchantId());
        }
        businesses.requireRoomFor(requester, info.merchantId());
        String sendTo = confirmationAddress(merchant, null);

        TransferRequest t = blank(TransferKind.BUSINESS_CLAIM, requester, now);
        t.setToDeveloperId(requester.getDeveloperId());
        t.setToEmail(requester.getEmail());
        describe(t, merchant);
        cancelOlder(requester, TransferKind.BUSINESS_CLAIM, null);
        String code = arm(t, sendTo, now);
        transfers.insert(t);
        sendCode(t, requester, sendTo, code, "let the developer account " + MaskedEmail.of(requester.getEmail())
                + " use this business, with the same merchant number, to go live");
        return TransferViews.view(t, requester.getDeveloperId(), now);
    }

    /** A new code for a request that is still open. Counts like a new request for the throttle. */
    public Map<String, Object> resend(Developer requester, String transferId) {
        TransferRequest t = mine(requester, transferId);
        Instant now = clock.instant();
        requirePending(t, now, true);
        throttle(requester, now);
        Map<String, Object> merchant = wallet.getMerchantView(t.getMerchantId());
        String sendTo = confirmationAddress(merchant, null);
        String code = arm(t, sendTo, now);
        transfers.save(t);
        sendCode(t, requester, sendTo, code, t.getKind() == TransferKind.APP_TRANSFER
                ? "move the production app \"" + t.getAppName() + "\" to another developer account (" + MaskedEmail.of(t.getToEmail()) + ")"
                : "let the developer account " + MaskedEmail.of(t.getToEmail()) + " use this business, with the same merchant number, to go live");
        return TransferViews.view(t, requester.getDeveloperId(), now);
    }

    // Finishing

    /** Checks the code and does what was asked. A wrong code is counted, and too many close the request. */
    public Map<String, Object> confirm(Developer requester, String transferId, String otp) {
        TransferRequest t = mine(requester, transferId);
        Instant now = clock.instant();
        requirePending(t, now, false);

        String expected = t.getOtpHash();
        if (otp == null || !Hashing.constantTimeEquals(expected, hash(t.getId(), otp.trim()))) {
            t.setAttempts(t.getAttempts() + 1);
            boolean closed = t.getAttempts() >= TransferViews.MAX_ATTEMPTS;
            if (closed) close(t, TransferStatus.CANCELLED, now);
            transfers.save(t);
            throw new DeveloperException(ErrorCode.OTP_INVALID, closed
                    ? "Too many wrong codes. This request is closed, start it again."
                    : "That code is not right. Check the email and try again.")
                    .with("attemptsLeft", Math.max(0, TransferViews.MAX_ATTEMPTS - t.getAttempts()));
        }

        if (t.getKind() == TransferKind.APP_TRANSFER) {
            completeAppTransfer(requester, t, now);
        } else {
            businesses.share(requester, t.getMerchantId());
        }
        close(t, TransferStatus.COMPLETED, now);
        transfers.save(t);
        return TransferViews.view(t, requester.getDeveloperId(), now);
    }

    public Map<String, Object> cancel(Developer requester, String transferId) {
        TransferRequest t = mine(requester, transferId);
        Instant now = clock.instant();
        if (t.getStatus() == TransferStatus.PENDING) {
            close(t, TransferStatus.CANCELLED, now);
            transfers.save(t);
        } else if (t.getStatus() == TransferStatus.COMPLETED) {
            throw DeveloperException.invalidState("This request is already done and cannot be cancelled");
        }
        return TransferViews.view(t, requester.getDeveloperId(), now);
    }

    // Reading

    public Map<String, Object> get(Developer developer, String transferId) {
        TransferRequest t = transfers.findById(transferId == null ? "" : transferId)
                .filter(x -> involves(x, developer))
                .orElseThrow(() -> new DeveloperException(ErrorCode.TRANSFER_NOT_FOUND, "Transfer not found"));
        return TransferViews.view(t, developer.getDeveloperId(), clock.instant());
    }

    /** The newest requests the account started, and the apps on their way to it. Refused lookups are not listed. */
    public Map<String, Object> list(Developer developer) {
        Instant now = clock.instant();
        List<Map<String, Object>> views = transfers
                .findFirst50ByRequesterIdOrToDeveloperIdOrderByCreatedAtDesc(developer.getDeveloperId(), developer.getDeveloperId())
                .stream()
                .filter(t -> t.getStatus() != TransferStatus.REJECTED)
                .filter(t -> t.getRequesterId().equals(developer.getDeveloperId()) || t.getKind() == TransferKind.APP_TRANSFER)
                .map(t -> TransferViews.view(t, developer.getDeveloperId(), now))
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transfers", views);
        out.put("count", views.size());
        return out;
    }

    // Steps

    /**
     * The order matters. The new account gets the business first, so a failure never leaves it with an app it cannot use;
     * the keys are revoked before the app changes hands, so the old account can never call the API with the app after it moved.
     */
    private void completeAppTransfer(Developer requester, TransferRequest t, Instant now) {
        DeveloperApp app = apps.findById(t.getAppId())
                .filter(a -> a.getDeveloperId().equals(requester.getDeveloperId()))
                .orElseThrow(() -> new DeveloperException(ErrorCode.APP_NOT_FOUND, "App not found. It may already have moved"));
        Developer to = developers.findById(t.getToDeveloperId()).filter(d -> d.getStatus() == DeveloperStatus.ACTIVE)
                .orElseThrow(() -> DeveloperException.invalidState("The account this app was going to is not available any more"));

        businesses.share(to, app.getMerchantId());
        int revoked = keys.revokeAll(app.getAppId());
        app.setDeveloperId(to.getDeveloperId());
        app.touch(now);
        apps.save(app);
        sync.push(app);
        log.info("App {} moved from developer {} to developer {}; {} keys revoked", app.getAppId(), requester.getDeveloperId(),
                to.getDeveloperId(), revoked);

        mail.later("app received", () -> {
            EmailTemplates.Message m = EmailTemplates.appReceived(greeting(to), greeting(requester), app.getName(),
                    t.getBusinessName(), t.getMerchantNumber(), portalUrl);
            mail.send(to.getEmail(), m.subject(), m.html(), null);
        });
        mail.later("app handed over", () -> {
            EmailTemplates.Message m = EmailTemplates.appHandedOver(greeting(requester), app.getName(), t.getBusinessName(),
                    t.getMerchantNumber(), MaskedEmail.of(to.getEmail()), portalUrl);
            mail.send(requester.getEmail(), m.subject(), m.html(), null);
        });
    }

    /**
     * An approved, live business by its number. Anything else, whether the number does not exist or the business is not
     * live yet, gets the same answer, and each miss is written down so that guessing numbers runs into the hourly cap.
     */
    private Map<String, Object> findApproved(Developer requester, String number, Instant now) {
        Map<String, Object> merchant = null;
        if (!number.isEmpty() && number.length() <= 40) {
            try {
                merchant = wallet.getMerchantViewByNumber(number);
            } catch (DeveloperException e) {
                if (e.code() != ErrorCode.MERCHANT_NOT_FOUND) throw e;
            }
        }
        if (merchant == null || !"ACTIVE".equals(MerchantInfo.from(merchant).liveStatus())) {
            TransferRequest miss = blank(TransferKind.BUSINESS_CLAIM, requester, now);
            miss.setStatus(TransferStatus.REJECTED);
            miss.setPurgeAt(now.plus(Duration.ofHours(2)));
            transfers.insert(miss);
            throw new DeveloperException(ErrorCode.BUSINESS_NOT_FOUND, "No approved business has that merchant number");
        }
        return merchant;
    }

    /** One code per cooldown, and a cap on all requests and misses in an hour. */
    private void throttle(Developer requester, Instant now) {
        transfers.findFirstByRequesterIdOrderByCreatedAtDesc(requester.getDeveloperId())
                .filter(last -> last.getOtpHash() != null)
                .ifPresent(last -> {
                    long millis = Duration.between(now, last.getCreatedAt().plusSeconds(rules.cooldownSeconds())).toMillis();
                    if (millis > 0) throw wait((millis + 999) / 1000, "A code was sent a moment ago");
                });
        long lastHour = transfers.countByRequesterIdAndCreatedAtAfter(requester.getDeveloperId(), now.minus(Duration.ofHours(1)));
        if (lastHour >= rules.perHour()) {
            throw wait(Duration.ofMinutes(10).toSeconds(), "Too many requests");
        }
    }

    private static DeveloperException wait(long seconds, String why) {
        return new DeveloperException(ErrorCode.RATE_LIMITED, why + ". Try again in " + seconds + " seconds.")
                .with("retryAfterSeconds", seconds);
    }

    private void cancelOlder(Developer requester, TransferKind kind, String appId) {
        Instant now = clock.instant();
        for (TransferRequest old : transfers.findByRequesterIdAndKindAndStatus(requester.getDeveloperId(), kind, TransferStatus.PENDING)) {
            if (appId == null || appId.equals(old.getAppId())) {
                close(old, TransferStatus.CANCELLED, now);
                transfers.save(old);
            }
        }
    }

    // Helpers

    private TransferRequest blank(TransferKind kind, Developer requester, Instant now) {
        TransferRequest t = new TransferRequest();
        t.setId(Ids.next("trf"));
        t.setKind(kind);
        t.setRequesterId(requester.getDeveloperId());
        t.setCreatedAt(now);
        return t;
    }

    private static void describe(TransferRequest t, Map<String, Object> merchant) {
        t.setMerchantId(String.valueOf(merchant.get("merchantId")));
        t.setMerchantNumber(merchant.get("merchantNumber") == null ? null : String.valueOf(merchant.get("merchantNumber")));
        t.setBusinessName(merchant.get("businessName") == null ? null : String.valueOf(merchant.get("businessName")));
    }

    /** Puts a new code on the request and returns it. Only its hash is kept. */
    private String arm(TransferRequest t, String sentTo, Instant now) {
        String code = codes.get();
        t.setOtpHash(hash(t.getId(), code));
        t.setOtpSentTo(MaskedEmail.of(sentTo));
        t.setAttempts(0);
        t.setExpiresAt(now.plus(Duration.ofMinutes(rules.codeMinutes())));
        t.setPurgeAt(now.plus(Duration.ofDays(rules.keepDays())));
        return code;
    }

    private void close(TransferRequest t, TransferStatus status, Instant now) {
        t.setStatus(status);
        t.setCompletedAt(now);
        t.setPurgeAt(now.plus(Duration.ofDays(rules.keepDays())));
    }

    private void sendCode(TransferRequest t, Developer requester, String sendTo, String code, String action) {
        mail.later("transfer code", () -> {
            EmailTemplates.Message m = EmailTemplates.transferCode(null, code, action, t.getBusinessName(), t.getMerchantNumber(),
                    requester.getFullName(), rules.codeMinutes(), portalUrl);
            mail.send(sendTo, m.subject(), m.html(), "Code " + code);
        });
    }

    private TransferRequest mine(Developer developer, String transferId) {
        return transfers.findById(transferId == null ? "" : transferId)
                .filter(t -> t.getRequesterId().equals(developer.getDeveloperId()) && t.getStatus() != TransferStatus.REJECTED)
                .orElseThrow(() -> new DeveloperException(ErrorCode.TRANSFER_NOT_FOUND, "Transfer not found"));
    }

    private static boolean involves(TransferRequest t, Developer d) {
        if (t.getStatus() == TransferStatus.REJECTED) return false;
        return t.getRequesterId().equals(d.getDeveloperId())
                || (t.getKind() == TransferKind.APP_TRANSFER && d.getDeveloperId().equals(t.getToDeveloperId()));
    }

    private void requirePending(TransferRequest t, Instant now, boolean allowExpired) {
        if (t.getStatus() != TransferStatus.PENDING) {
            throw DeveloperException.invalidState("This request is " + t.getStatus().name().toLowerCase(Locale.ROOT)
                    + ". Start a new one if you still need it");
        }
        if (!allowExpired && !now.isBefore(t.getExpiresAt())) {
            throw new DeveloperException(ErrorCode.OTP_EXPIRED, "The code has expired. Ask for a new one");
        }
    }

    private String hash(String transferId, String code) {
        return Hashing.hmacSha256Hex(pepper, "transfer-otp:" + transferId + ":" + code);
    }

    /** The wallet account on the application, else the business's contact address, else the fallback. */
    @SuppressWarnings("unchecked")
    static String confirmationAddress(Map<String, Object> merchant, String fallback) {
        if (merchant.get("onboarding") instanceof Map<?, ?> o) {
            Map<String, Object> summary = (Map<String, Object>) o;
            for (String key : new String[] {"walletAccount", "contactEmail"}) {
                Object v = summary.get(key);
                if (v != null && String.valueOf(v).contains("@")) return String.valueOf(v).trim();
            }
        }
        if (fallback != null && fallback.contains("@")) return fallback.trim();
        throw DeveloperException.invalidState(
                "This business has no email address linked to it, so the owner cannot be asked. Contact Premisave support");
    }

    private static String greeting(Developer d) {
        return d.getFirstName() != null && !d.getFirstName().isBlank() ? d.getFirstName() : d.getFullName();
    }

    private static String randomCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}

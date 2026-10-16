package com.premisave.developer.developer;

import com.premisave.developer.common.DeveloperException;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.wallet.MerchantInfo;
import com.premisave.developer.wallet.WalletApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Connects a developer to a merchant in the wallet service. The merchant itself, with its onboarding, lives there. */
@Service("developerMerchantLinkService")
public class MerchantLinkService {

    private final DeveloperRepository developers;
    private final WalletApi wallet;
    private final boolean requireVerifiedEmail;

    @Autowired
    public MerchantLinkService(DeveloperRepository developers, WalletApi wallet, DeveloperProperties props) {
        this(developers, wallet, props.mail().requireVerifiedForBusiness());
    }

    public MerchantLinkService(DeveloperRepository developers, WalletApi wallet, boolean requireVerifiedEmail) {
        this.developers = developers;
        this.wallet = wallet;
        this.requireVerifiedEmail = requireVerifiedEmail;
    }

    /** Creates the merchant (and its onboarding application) for this developer. Safe to repeat after a failure. */
    public Map<String, Object> create(Developer developer, CreateBusinessRequest req) {
        if (requireVerifiedEmail && !developer.isEmailVerified()) {
            throw new DeveloperException(ErrorCode.EMAIL_NOT_VERIFIED,
                    "Confirm your email address first. Use the link we sent you, or ask for a new one.");
        }
        if (developer.getMerchantId() != null) {
            throw new DeveloperException(ErrorCode.MERCHANT_EXISTS, "This account already has a business")
                    .with("merchantId", developer.getMerchantId());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("developerAccountId", developer.getDeveloperId());
        body.put("businessName", req.businessName().trim());
        body.put("displayName", req.displayName());
        body.put("country", req.country().toUpperCase(Locale.ROOT));
        body.put("contactEmail", req.contactEmail() == null || req.contactEmail().isBlank()
                ? developer.getEmail() : req.contactEmail().trim());
        body.put("contactPhone", req.contactPhone());
        body.put("defaultCurrency", req.defaultCurrency());
        body.put("requestedMethods", req.requestedMethods() == null ? null
                : req.requestedMethods().stream().map(Enum::name).sorted().toList());

        Map<String, Object> merchant;
        try {
            merchant = wallet.createMerchant(body);
        } catch (DeveloperException e) {
            // The wallet service already has a merchant for this developer, for example after a lost reply.
            if (e.code() == ErrorCode.DUPLICATE_REQUEST && e.extra().get("merchantId") != null) {
                String existing = String.valueOf(e.extra().get("merchantId"));
                link(developer, existing);
                return wallet.getMerchantView(existing);
            }
            throw e;
        }
        link(developer, String.valueOf(merchant.get("merchantId")));
        return merchant;
    }

    public Map<String, Object> view(Developer developer) {
        return wallet.getMerchantView(requireMerchantId(developer));
    }

    public MerchantInfo info(Developer developer) {
        return wallet.getMerchant(requireMerchantId(developer));
    }

    public static String requireMerchantId(Developer developer) {
        if (developer.getMerchantId() == null) {
            throw new DeveloperException(ErrorCode.MERCHANT_REQUIRED, "Add your business details first");
        }
        return developer.getMerchantId();
    }

    private void link(Developer developer, String merchantId) {
        developer.setMerchantId(merchantId);
        developer.setUpdatedAt(Instant.now());
        developers.save(developer);
    }
}

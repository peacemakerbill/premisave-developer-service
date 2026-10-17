package com.premisave.developer.service;

import com.premisave.developer.client.WalletApi;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.dto.ApplyBusinessRequest;
import com.premisave.developer.dto.MerchantInfo;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.repository.DeveloperRepository;
import com.premisave.developer.view.BusinessViews;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The businesses of a developer account. Each is a merchant in the wallet service with its own onboarding application,
 * and one account can apply for many. This service keeps the list of ids that belong to the account, applies for new
 * ones, and checks that a business named in a request is the caller's own. The merchant itself lives in the wallet service.
 */
@Service("developerBusinessService")
public class BusinessService {

    private final DeveloperRepository developers;
    private final WalletApi wallet;
    private final boolean requireVerifiedEmail;
    private final int maxPerDeveloper;

    @Autowired
    public BusinessService(DeveloperRepository developers, WalletApi wallet, DeveloperProperties props) {
        this(developers, wallet, props.mail().requireVerifiedForBusiness(), props.businesses().maxPerDeveloper());
    }

    public BusinessService(DeveloperRepository developers, WalletApi wallet, boolean requireVerifiedEmail, int maxPerDeveloper) {
        this.developers = developers;
        this.wallet = wallet;
        this.requireVerifiedEmail = requireVerifiedEmail;
        this.maxPerDeveloper = maxPerDeveloper;
    }

    /**
     * Applies for a business: the merchant and its draft onboarding application are created in the wallet service. Safe
     * to repeat after a failure. Applying for the same business twice (same name and country) is refused, unless the
     * first attempt never reached this account, in which case it is picked up instead of duplicated.
     */
    public Map<String, Object> apply(Developer developer, ApplyBusinessRequest req) {
        if (requireVerifiedEmail && !developer.isEmailVerified()) {
            throw new DeveloperException(ErrorCode.EMAIL_NOT_VERIFIED,
                    "Confirm your email address first. Use the link we sent you, or ask for a new one.");
        }
        if (developer.businessIds().size() >= maxPerDeveloper) {
            throw new DeveloperException(ErrorCode.LIMIT_REACHED,
                    "One account can have up to " + maxPerDeveloper + " businesses");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("developerAccountId", developer.getDeveloperId());
        body.put("businessName", req.name().trim());
        body.put("displayName", req.tradingName());
        body.put("country", req.country().toUpperCase(Locale.ROOT));
        body.put("contactEmail", req.contactEmail() == null || req.contactEmail().isBlank()
                ? developer.getEmail() : req.contactEmail().trim());
        body.put("contactPhone", req.contactPhone());
        body.put("defaultCurrency", req.currency());
        body.put("requestedMethods", req.paymentMethods() == null ? null
                : req.paymentMethods().stream().map(Enum::name).sorted().toList());

        Map<String, Object> merchant;
        try {
            merchant = wallet.createMerchant(body);
        } catch (DeveloperException e) {
            if (e.code() == ErrorCode.DUPLICATE_REQUEST && e.extra().get("merchantId") != null) {
                String existing = String.valueOf(e.extra().get("merchantId"));
                if (developer.ownsBusiness(existing)) {
                    throw new DeveloperException(ErrorCode.BUSINESS_EXISTS,
                            "You have already applied for this business. Open it from your list of businesses.")
                            .with("businessId", existing);
                }
                // The wallet service has it but this account does not, for example after a lost reply. Pick it up.
                link(developer, existing);
                return BusinessViews.business(wallet.getMerchantView(existing));
            }
            throw e;
        }
        link(developer, String.valueOf(merchant.get("merchantId")));
        return BusinessViews.business(merchant);
    }

    /**
     * Every business of the account with where each application stands. The wallet service is asked for the list, and
     * any business it has for this account that the account does not know yet is added, so the list always matches.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> list(Developer developer) {
        Map<String, Object> data = wallet.listMerchants(developer.getDeveloperId());
        List<Map<String, Object>> raw = data.get("businesses") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        boolean changed = false;
        List<Map<String, Object>> views = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (Map<String, Object> merchant : raw) {
            String id = String.valueOf(merchant.get("merchantId"));
            if (!developer.ownsBusiness(id)) {
                developer.addBusiness(id);
                changed = true;
            }
            listed.add(id);
            views.add(BusinessViews.business(merchant, !developer.appliedFor(id)));
        }
        // Businesses the account was given use of are not in the wallet service's list for this account.
        for (String id : developer.businessIds()) {
            if (listed.contains(id)) continue;
            try {
                views.add(BusinessViews.business(wallet.getMerchantView(id), !developer.appliedFor(id)));
            } catch (DeveloperException e) {
                if (e.code() != ErrorCode.MERCHANT_NOT_FOUND) throw e;
            }
        }
        if (changed) {
            developer.setUpdatedAt(Instant.now());
            developers.save(developer);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businesses", views);
        out.put("count", views.size());
        out.put("limit", maxPerDeveloper);
        return out;
    }

    public Map<String, Object> view(Developer developer, String businessId) {
        String id = requireOwned(developer, businessId);
        return BusinessViews.business(wallet.getMerchantView(id), !developer.appliedFor(id));
    }

    /**
     * The id of a business whose application the caller may open: one the account applied for. A business that was only
     * shared with the account has an application the account never saw, so that is refused.
     */
    public String requireApplicant(Developer developer, String businessId) {
        String id = requireOwned(developer, businessId);
        if (!developer.appliedFor(id)) {
            throw new DeveloperException(ErrorCode.FORBIDDEN,
                    "Only the account that applied for this business can open its application");
        }
        return id;
    }

    /** Refuses with LIMIT_REACHED when taking one more business would pass the cap. A business already on the account is fine. */
    public void requireRoomFor(Developer developer, String businessId) {
        if (!developer.ownsBusiness(businessId) && developer.businessIds().size() >= maxPerDeveloper) {
            throw new DeveloperException(ErrorCode.LIMIT_REACHED, "One account can have up to " + maxPerDeveloper + " businesses");
        }
    }

    /** Gives the account use of a business it did not apply for. Safe to repeat. */
    public void share(Developer developer, String businessId) {
        if (developer.ownsBusiness(businessId)) return;
        requireRoomFor(developer, businessId);
        developer.addSharedBusiness(businessId);
        developer.setUpdatedAt(Instant.now());
        developers.save(developer);
    }

    /** The wallet account (an email address) named on the business's application, or null when none is. */
    @SuppressWarnings("unchecked")
    public String linkedWalletAccount(String businessId) {
        Object onboarding = wallet.getMerchantView(businessId).get("onboarding");
        if (!(onboarding instanceof Map<?, ?> m)) return null;
        Object account = ((Map<String, Object>) m).get("walletAccount");
        return account == null || String.valueOf(account).isBlank() ? null : String.valueOf(account);
    }

    public MerchantInfo info(Developer developer, String businessId) {
        return wallet.getMerchant(requireOwned(developer, businessId));
    }

    /** The id of a business that belongs to this account. Someone else's business looks the same as one that does not exist. */
    public String requireOwned(Developer developer, String businessId) {
        if (!developer.ownsBusiness(businessId)) {
            throw new DeveloperException(ErrorCode.BUSINESS_NOT_FOUND, "Business not found");
        }
        return businessId;
    }

    /**
     * The business a request is about. With an id it must be the account's own. Without one the account's only business
     * is used, and an account with none or several is told what to do.
     */
    public String resolve(Developer developer, String businessId) {
        if (businessId != null && !businessId.isBlank()) return requireOwned(developer, businessId.trim());
        List<String> ids = developer.businessIds();
        if (ids.isEmpty()) {
            throw new DeveloperException(ErrorCode.BUSINESS_REQUIRED,
                    "Apply for a business first, with POST /api/v1/businesses");
        }
        if (ids.size() > 1) {
            throw DeveloperException.validation("businessId", "is required because this account has more than one business")
                    .with("businessIds", ids);
        }
        return ids.get(0);
    }

    private void link(Developer developer, String businessId) {
        developer.addBusiness(businessId);
        developer.setUpdatedAt(Instant.now());
        developers.save(developer);
    }
}

package com.premisave.developer.service;

import com.premisave.developer.TestRepos;
import com.premisave.developer.dto.CreateBusinessRequest;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The business can only be created once the email address is confirmed. The wallet is null here, so any call to it fails loudly. */
class MerchantLinkServiceTest {

    private final TestRepos.Developers devs = new TestRepos.Developers();

    private static CreateBusinessRequest request() {
        return new CreateBusinessRequest("Acme Ltd", null, "ke", null, null, null, null);
    }

    private Developer developer(boolean verified, String merchantId) {
        Developer d = new Developer();
        d.setDeveloperId("dev_1");
        d.setEmail("a@example.com");
        d.setEmailVerified(verified);
        d.setMerchantId(merchantId);
        return d;
    }

    @Test
    void anUnconfirmedAddressIsRefusedBeforeTheWalletIsContacted() {
        MerchantLinkService service = new MerchantLinkService(devs.repo, null, true);
        DeveloperException e = assertThrows(DeveloperException.class, () -> service.create(developer(false, null), request()));
        assertEquals(ErrorCode.EMAIL_NOT_VERIFIED, e.code());
        assertEquals(403, e.code().httpStatus());
    }

    @Test
    void aConfirmedAddressGetsPastTheGate() {
        MerchantLinkService service = new MerchantLinkService(devs.repo, null, true);
        DeveloperException e = assertThrows(DeveloperException.class, () -> service.create(developer(true, "mer_1"), request()));
        assertEquals(ErrorCode.MERCHANT_EXISTS, e.code());
    }

    @Test
    void theGateCanBeSwitchedOff() {
        MerchantLinkService service = new MerchantLinkService(devs.repo, null, false);
        DeveloperException e = assertThrows(DeveloperException.class, () -> service.create(developer(false, "mer_1"), request()));
        assertEquals(ErrorCode.MERCHANT_EXISTS, e.code());
    }
}

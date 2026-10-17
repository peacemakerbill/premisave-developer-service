package com.premisave.developer.service;

import com.premisave.developer.TestRepos;
import com.premisave.developer.client.WalletApi;
import com.premisave.developer.dto.ApplyBusinessRequest;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One account, many businesses: applying, the duplicate rule, the cap, the list that repairs itself, and ownership. */
class BusinessServiceTest {

    /** A wallet service that keeps merchants in a list, and refuses a second one with the same name and country. */
    static class FakeWallet extends WalletApi {
        final List<Map<String, Object>> merchants = new ArrayList<>();
        final List<Map<String, Object>> created = new ArrayList<>();
        /** When set, createMerchant answers DUPLICATE_REQUEST for this id once, as if a lost reply had left it behind. */
        String duplicateOf;

        FakeWallet() {
            super(null, null);
        }

        @Override
        public Map<String, Object> createMerchant(Map<String, Object> body) {
            if (duplicateOf != null) {
                String id = duplicateOf;
                duplicateOf = null;
                throw new DeveloperException(ErrorCode.DUPLICATE_REQUEST, "exists").with("merchantId", id);
            }
            created.add(body);
            return add("mer_" + (merchants.size() + 1), String.valueOf(body.get("businessName")), "DRAFT");
        }

        Map<String, Object> add(String id, String name, String applicationStatus) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("merchantId", id);
            m.put("merchantNumber", "PRM-" + id);
            m.put("businessName", name);
            m.put("country", "KE");
            m.put("sandbox", Map.of("status", "ACTIVE"));
            m.put("live", Map.of("status", "NOT_ENABLED"));
            m.put("onboarding", Map.of("applicationId", "app_" + id, "status", applicationStatus, "readyToSubmit", false,
                    "missing", List.of("director")));
            merchants.add(m);
            return m;
        }

        @Override
        public Map<String, Object> listMerchants(String developerAccountId) {
            return Map.of("businesses", merchants);
        }

        @Override
        public Map<String, Object> getMerchantView(String merchantId) {
            return merchants.stream().filter(m -> merchantId.equals(m.get("merchantId"))).findFirst().orElseThrow();
        }
    }

    private final TestRepos.Developers devs = new TestRepos.Developers();
    private final FakeWallet wallet = new FakeWallet();

    private static ApplyBusinessRequest request(String name) {
        return new ApplyBusinessRequest(name, null, "ke", null, null, null, null);
    }

    private Developer developer(boolean verified) {
        Developer d = new Developer();
        d.setDeveloperId("dev_1");
        d.setEmail("a@example.com");
        d.setEmailVerified(verified);
        return d;
    }

    private BusinessService service(boolean gate, int max) {
        return new BusinessService(devs.repo, wallet, gate, max);
    }

    private static DeveloperException fails(Runnable r) {
        return assertThrows(DeveloperException.class, r::run);
    }

    @Test
    void anUnconfirmedAddressIsRefusedBeforeTheWalletIsContacted() {
        DeveloperException e = fails(() -> service(true, 10).apply(developer(false), request("Acme")));
        assertEquals(ErrorCode.EMAIL_NOT_VERIFIED, e.code());
        assertEquals(403, e.code().httpStatus());
        assertTrue(wallet.created.isEmpty());
    }

    @Test
    void theGateCanBeSwitchedOff() {
        Map<String, Object> b = service(false, 10).apply(developer(false), request("Acme"));
        assertEquals("mer_1", b.get("businessId"));
    }

    @Test
    void oneAccountCanApplyForManyBusinesses() {
        Developer d = developer(true);
        BusinessService s = service(true, 10);
        assertEquals("mer_1", s.apply(d, request("Acme")).get("businessId"));
        assertEquals("mer_2", s.apply(d, request("Beta Traders")).get("businessId"));
        assertEquals(List.of("mer_1", "mer_2"), d.businessIds());
        assertEquals(d.businessIds(), devs.store.get("dev_1").businessIds());
    }

    @Test
    void theWalletGetsTheAccountAndTheContactDefaultsToTheAccountEmail() {
        service(true, 10).apply(developer(true), request(" Acme Ltd "));
        Map<String, Object> body = wallet.created.get(0);
        assertEquals("dev_1", body.get("developerAccountId"));
        assertEquals("Acme Ltd", body.get("businessName"));
        assertEquals("KE", body.get("country"));
        assertEquals("a@example.com", body.get("contactEmail"));
    }

    @Test
    void aBusinessAlreadyOnTheAccountIsRefusedWithItsId() {
        Developer d = developer(true);
        d.addBusiness("mer_7");
        wallet.duplicateOf = "mer_7";
        DeveloperException e = fails(() -> service(true, 10).apply(d, request("Acme")));
        assertEquals(ErrorCode.BUSINESS_EXISTS, e.code());
        assertEquals("mer_7", e.extra().get("businessId"));
    }

    @Test
    void aBusinessTheWalletHasButTheAccountLostIsPickedUpNotDuplicated() {
        wallet.add("mer_7", "Acme", "DRAFT");
        wallet.duplicateOf = "mer_7";
        Developer d = developer(true);
        Map<String, Object> b = service(true, 10).apply(d, request("Acme"));
        assertEquals("mer_7", b.get("businessId"));
        assertEquals(List.of("mer_7"), d.businessIds());
        assertTrue(wallet.created.isEmpty());
    }

    @Test
    void otherWalletFailuresAreNotHidden() {
        FakeWallet failing = new FakeWallet() {
            @Override
            public Map<String, Object> createMerchant(Map<String, Object> body) {
                throw new DeveloperException(ErrorCode.UPSTREAM_UNAVAILABLE, "down");
            }
        };
        Developer d = developer(true);
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE,
                fails(() -> new BusinessService(devs.repo, failing, true, 10).apply(d, request("Acme"))).code());
        assertTrue(d.businessIds().isEmpty());
    }

    @Test
    void thereIsALimitOnBusinessesPerAccount() {
        Developer d = developer(true);
        BusinessService s = service(true, 2);
        s.apply(d, request("One"));
        s.apply(d, request("Two"));
        DeveloperException e = fails(() -> s.apply(d, request("Three")));
        assertEquals(ErrorCode.LIMIT_REACHED, e.code());
        assertEquals(2, wallet.created.size());
    }

    @Test
    void listShowsEveryBusinessWithItsNextStepAndRepairsMissingLinks() {
        wallet.add("mer_1", "Acme", "DRAFT");
        wallet.add("mer_2", "Beta", "APPROVED");
        Developer d = developer(true);
        d.addBusiness("mer_1");
        Map<String, Object> out = service(true, 10).list(d);
        assertEquals(2, out.get("count"));
        assertEquals(10, out.get("limit"));
        assertEquals(List.of("mer_1", "mer_2"), d.businessIds());
        assertEquals(List.of("mer_1", "mer_2"), devs.store.get("dev_1").businessIds());
        List<?> shown = (List<?>) out.get("businesses");
        assertEquals("COMPLETE_APPLICATION", ((Map<?, ?>) ((Map<?, ?>) shown.get(0)).get("nextStep")).get("code"));
        assertEquals("CREATE_APP", ((Map<?, ?>) ((Map<?, ?>) shown.get(1)).get("nextStep")).get("code"));
    }

    @Test
    void listWithNothingToRepairDoesNotWrite() {
        wallet.add("mer_1", "Acme", "DRAFT");
        Developer d = developer(true);
        d.addBusiness("mer_1");
        service(true, 10).list(d);
        assertTrue(devs.store.isEmpty());
    }

    @Test
    void anAccountSeesOnlyItsOwnBusinesses() {
        wallet.add("mer_1", "Acme", "DRAFT");
        Developer stranger = developer(true);
        stranger.setDeveloperId("dev_2");
        assertEquals(ErrorCode.BUSINESS_NOT_FOUND, fails(() -> service(true, 10).view(stranger, "mer_1")).code());
        assertEquals(ErrorCode.BUSINESS_NOT_FOUND, fails(() -> service(true, 10).info(stranger, "mer_1")).code());
        assertEquals(ErrorCode.BUSINESS_NOT_FOUND, fails(() -> service(true, 10).requireOwned(stranger, "nope")).code());
    }

    @Test
    void viewReturnsTheBusinessInThePortalShape() {
        wallet.add("mer_1", "Acme", "DRAFT");
        Developer d = developer(true);
        d.addBusiness("mer_1");
        Map<String, Object> v = service(true, 10).view(d, "mer_1");
        assertEquals("mer_1", v.get("businessId"));
        assertEquals("Acme", v.get("name"));
        assertFalse(v.containsKey("merchantId"));
    }

    @Test
    void resolveUsesTheOnlyBusinessOrTheOneNamed() {
        Developer one = developer(true);
        one.addBusiness("mer_1");
        BusinessService s = service(true, 10);
        assertEquals("mer_1", s.resolve(one, null));
        assertEquals("mer_1", s.resolve(one, " "));
        assertEquals("mer_1", s.resolve(one, " mer_1 "));
    }

    @Test
    void resolveExplainsWhatToDoWithNoBusinessOrSeveral() {
        BusinessService s = service(true, 10);
        Developer none = developer(true);
        assertEquals(ErrorCode.BUSINESS_REQUIRED, fails(() -> s.resolve(none, null)).code());
        Developer many = developer(true);
        many.addBusiness("mer_1");
        many.addBusiness("mer_2");
        DeveloperException e = fails(() -> s.resolve(many, null));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        assertEquals(List.of("mer_1", "mer_2"), e.extra().get("businessIds"));
        assertEquals("mer_2", s.resolve(many, "mer_2"));
        assertEquals(ErrorCode.BUSINESS_NOT_FOUND, fails(() -> s.resolve(many, "mer_3")).code());
    }

    @Test
    void listIncludesBusinessesSharedWithTheAccountAndMarksThem() {
        wallet.add("mer_1", "Acme", "DRAFT");
        wallet.add("mer_9", "Gamma", "APPROVED");
        Developer d = developer(true);
        d.addBusiness("mer_1");
        d.addSharedBusiness("mer_9");
        BusinessService s = new BusinessService(devs.repo, new FakeWallet() {
            @Override
            public Map<String, Object> listMerchants(String developerAccountId) {
                return Map.of("businesses", List.of(wallet.merchants.get(0)));
            }

            @Override
            public Map<String, Object> getMerchantView(String merchantId) {
                return wallet.getMerchantView(merchantId);
            }
        }, true, 10);
        Map<String, Object> out = s.list(d);
        assertEquals(2, out.get("count"));
        List<?> shown = (List<?>) out.get("businesses");
        assertEquals("OWNER", ((Map<?, ?>) shown.get(0)).get("access"));
        assertEquals("SHARED", ((Map<?, ?>) shown.get(1)).get("access"));
        assertEquals("mer_9", ((Map<?, ?>) shown.get(1)).get("businessId"));
        assertTrue(devs.store.isEmpty());
    }

    @Test
    void aSharedBusinessThatNoLongerExistsIsLeftOutOfTheList() {
        Developer d = developer(true);
        d.addSharedBusiness("mer_gone");
        BusinessService s = new BusinessService(devs.repo, new FakeWallet() {
            @Override
            public Map<String, Object> getMerchantView(String merchantId) {
                throw new DeveloperException(ErrorCode.MERCHANT_NOT_FOUND, "gone");
            }
        }, true, 10);
        assertEquals(0, s.list(d).get("count"));
    }

    @Test
    void aSharedBusinessCanBeViewedButItsApplicationBelongsToTheApplicant() {
        wallet.add("mer_1", "Acme", "DRAFT");
        Developer guest = developer(true);
        guest.setDeveloperId("dev_2");
        guest.addSharedBusiness("mer_1");
        BusinessService s = service(true, 10);
        Map<String, Object> v = s.view(guest, "mer_1");
        assertEquals("SHARED", v.get("access"));
        assertNull(v.get("application"));
        assertEquals(ErrorCode.FORBIDDEN, fails(() -> s.requireApplicant(guest, "mer_1")).code());

        Developer applicant = developer(true);
        applicant.addBusiness("mer_1");
        assertEquals("mer_1", s.requireApplicant(applicant, "mer_1"));
        assertEquals(ErrorCode.BUSINESS_NOT_FOUND, fails(() -> s.requireApplicant(applicant, "mer_2")).code());
    }

    @Test
    void sharingGivesUseOnceAndIsSavedAndRepeatable() {
        Developer guest = developer(true);
        guest.setDeveloperId("dev_2");
        BusinessService s = service(true, 10);
        s.share(guest, "mer_9");
        assertEquals(List.of("mer_9"), devs.store.get("dev_2").getSharedMerchantIds());
        devs.store.clear();
        s.share(guest, "mer_9");
        assertTrue(devs.store.isEmpty());
        assertEquals(List.of("mer_9"), guest.getSharedMerchantIds());
    }

    @Test
    void sharingCountsAgainstTheBusinessLimitButAnExistingOneDoesNot() {
        Developer guest = developer(true);
        guest.setDeveloperId("dev_2");
        guest.addBusiness("mer_1");
        BusinessService s = service(true, 1);
        s.requireRoomFor(guest, "mer_1");
        assertEquals(ErrorCode.LIMIT_REACHED, fails(() -> s.requireRoomFor(guest, "mer_2")).code());
        assertEquals(ErrorCode.LIMIT_REACHED, fails(() -> s.share(guest, "mer_2")).code());
        assertTrue(guest.getSharedMerchantIds().isEmpty());
    }

    @Test
    void theWalletAccountOnTheApplicationIsFoundOrNull() {
        Map<String, Object> m = wallet.add("mer_1", "Acme", "DRAFT");
        BusinessService s = service(true, 10);
        assertNull(s.linkedWalletAccount("mer_1"));
        m.put("onboarding", Map.of("walletAccount", "owner@acme.test"));
        assertEquals("owner@acme.test", s.linkedWalletAccount("mer_1"));
        m.put("onboarding", Map.of("walletAccount", " "));
        assertNull(s.linkedWalletAccount("mer_1"));
        m.remove("onboarding");
        assertNull(s.linkedWalletAccount("mer_1"));
    }
}

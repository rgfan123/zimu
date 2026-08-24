package cn.zimu.fulfillment.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ImportedCustomerIdentityTest {

    @Test
    void ordinaryCountryCodeAndFormattingDoNotCreateAnotherCustomerIdentity() {
        ImportedCustomerIdentity formatted = ImportedCustomerIdentity.from(
                " 聚福宝 收货人 ", "+86 （138） 0000-0000");
        ImportedCustomerIdentity canonical = ImportedCustomerIdentity.from(
                "聚福宝 收货人", "13800000000");

        assertThat(formatted.complete()).isTrue();
        assertThat(formatted.normalizedPhone()).isEqualTo("13800000000");
        assertThat(formatted.sourceCustomerRef()).isEqualTo(canonical.sourceCustomerRef());
    }

    @Test
    void lookupCandidatesIncludeHistoricalCountryCodeAndMasterCompatibleLockKeys() {
        ImportedCustomerIdentity canonical = ImportedCustomerIdentity.from("聚福宝收货人", "13800000000");
        ImportedCustomerIdentity historical = ImportedCustomerIdentity.legacyFrom(
                "聚福宝收货人", "+8613800000000");

        List<ImportedCustomerIdentity> candidates = ImportedCustomerIdentity.lookupCandidates(
                "聚福宝收货人", "13800000000");

        assertThat(candidates).extracting(ImportedCustomerIdentity::sourceCustomerRef)
                .containsExactly(canonical.sourceCustomerRef(), historical.sourceCustomerRef());
        assertThat(candidates).extracting(ImportedCustomerIdentity::advisoryLockKey)
                .containsExactly(
                        "import-customer:" + canonical.identityHash(),
                        "import-customer:" + historical.identityHash());
        assertThat(candidates).allSatisfy(candidate ->
                assertThat(candidate.identityHash()).matches("[0-9a-f]{64}"));
    }
}

package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.order.SourceBatchConfirmer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceBatchAutomaticReleaseServiceTest {

    @Test
    void reconciliationRequiredIsNotDowngradedToOrdinaryReview() {
        SourceTemplateProfileService profiles = mock(SourceTemplateProfileService.class);
        SourceBatchConfirmer sourceBatches = mock(SourceBatchConfirmer.class);
        SourceTemplateProfileService.TrustedTemplate profile =
                new SourceTemplateProfileService.TrustedTemplate(
                        7L,
                        "TPL-RECON-001",
                        SourceChannel.DAZHE,
                        "DAZHE_SOURCE_ORDER",
                        "v1",
                        "DAZHE-v1-recon",
                        "TRUSTED",
                        1L,
                        OffsetDateTime.now());
        when(profiles.trustedForBatch(42L)).thenReturn(Optional.of(profile));
        when(profiles.consumedRelease(42L)).thenReturn(
                Optional.empty(),
                Optional.of(new SourceTemplateProfileService.ConsumedRelease(
                        profile.id(), profile.profileNo(), "CONFIRMED_PENDING_OUTBOUND")));
        when(sourceBatches.confirmTrustedSourceBatch(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(IdempotentResult.executed(Map.of(), 200));
        when(sourceBatches.submitJdOutboundsForSourceBatch(anyLong(), any()))
                .thenReturn(Map.of(
                        "submitted_count", 1,
                        "skipped_count", 0,
                        "failed_count", 1,
                        "items", List.of(Map.of(
                                "shipment_id", "99",
                                "business_code", "RECONCILIATION_REQUIRED",
                                "message", "京东结果未知"))));
        SourceBatchAutomaticReleaseService service =
                new SourceBatchAutomaticReleaseService(profiles, sourceBatches, "system-release");

        assertThatThrownBy(() -> service.releaseIfTrusted(42L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getBusinessCode())
                                .isEqualTo("RECONCILIATION_REQUIRED"));
    }

    @Test
    void confirmedBatchWithoutPersistedAutomaticAuthorizationFailsBeforeOutbound() {
        SourceTemplateProfileService profiles = mock(SourceTemplateProfileService.class);
        SourceBatchConfirmer sourceBatches = mock(SourceBatchConfirmer.class);
        SourceTemplateProfileService.TrustedTemplate profile =
                new SourceTemplateProfileService.TrustedTemplate(
                        8L,
                        "TPL-LEGACY-001",
                        SourceChannel.DAZHE,
                        "DAZHE_SOURCE_ORDER",
                        "v1",
                        "DAZHE-v1-legacy",
                        "TRUSTED",
                        1L,
                        OffsetDateTime.now());
        when(profiles.trustedForBatch(43L)).thenReturn(Optional.of(profile));
        when(profiles.consumedRelease(43L)).thenReturn(Optional.empty());
        when(sourceBatches.confirmTrustedSourceBatch(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(IdempotentResult.executed(Map.of(), 200));
        SourceBatchAutomaticReleaseService service =
                new SourceBatchAutomaticReleaseService(profiles, sourceBatches, "system-release");

        assertThatThrownBy(() -> service.releaseIfTrusted(43L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getBusinessCode())
                                .isEqualTo("AUTOMATIC_RELEASE_STATE_INVALID"));
        verify(sourceBatches, never()).submitJdOutboundsForSourceBatch(anyLong(), any());
    }
}

package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ChannelIdentityServiceTest {

    private final ChannelIdentityRepository identities = mock(ChannelIdentityRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChannelIdentityService service = new ChannelIdentityService(identities, jdbc);

    @Test
    void bindCreatesBindingWithUniqueScopeAndSnapshot() {
        when(identities.findByCorpIdAndAccessTypeAndChannelIdentity("corp-1", "WECOM_CUSTOMER_CONTACT", "ext-1"))
                .thenReturn(Optional.empty());
        when(identities.save(any(ChannelIdentity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChannelIdentity binding = service.bind(
                "corp-1",
                "WECOM_CUSTOMER_CONTACT",
                "ext-1",
                42L,
                Map.of(
                        "display_name", "张三",
                        "remark", "老客户",
                        "description", "合作三年",
                        "avatar_url", "https://example.com/a.png"));

        assertThat(binding.getCorpId()).isEqualTo("corp-1");
        assertThat(binding.getAccessType()).isEqualTo("WECOM_CUSTOMER_CONTACT");
        assertThat(binding.getChannelIdentity()).isEqualTo("ext-1");
        assertThat(binding.getCustomerId()).isEqualTo(42L);
        assertThat(binding.getDisplayName()).isEqualTo("张三");
        assertThat(binding.getRemark()).isEqualTo("老客户");
        assertThat(binding.getDescription()).isEqualTo("合作三年");
        assertThat(binding.getAvatarUrl()).isEqualTo("https://example.com/a.png");
        verify(identities).save(binding);
    }

    @Test
    void bindIsIdempotentForSameIdentityAndCustomer() {
        ChannelIdentity existing = new ChannelIdentity();
        existing.setCustomerId(42L);
        when(identities.findByCorpIdAndAccessTypeAndChannelIdentity("corp-1", "WECOM_CUSTOMER_CONTACT", "ext-1"))
                .thenReturn(Optional.of(existing));
        when(identities.save(any(ChannelIdentity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChannelIdentity result =
                service.bind("corp-1", "WECOM_CUSTOMER_CONTACT", "ext-1", 42L, Map.of("display_name", "新昵称"));

        assertThat(result).isSameAs(existing);
        assertThat(result.getCustomerId()).isEqualTo(42L);
        assertThat(result.getDisplayName()).isEqualTo("新昵称");
        verify(identities).save(existing);
    }

    @Test
    void bindRejectsConflictWhenAlreadyBoundToAnotherCustomer() {
        ChannelIdentity existing = new ChannelIdentity();
        existing.setCustomerId(7L);
        when(identities.findByCorpIdAndAccessTypeAndChannelIdentity("corp-1", "WECOM_CUSTOMER_CONTACT", "ext-1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() ->
                        service.bind("corp-1", "WECOM_CUSTOMER_CONTACT", "ext-1", 42L, Map.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getBusinessCode()).isEqualTo("CHANNEL_IDENTITY_CONFLICT");
                    assertThat(business.getHttpStatus()).isEqualTo(409);
                });
    }

    @Test
    void bindRejectsIncompleteScope() {
        assertThatThrownBy(() -> service.bind("", "WECOM_CUSTOMER_CONTACT", "ext-1", 42L, Map.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("CHANNEL_IDENTITY_INVALID"));
        assertThatThrownBy(() -> service.bind("corp-1", "  ", "ext-1", 42L, Map.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("CHANNEL_IDENTITY_INVALID"));
    }

    @Test
    void findBoundReturnsOnlyCustomerBoundIdentities() {
        ChannelIdentity unbound = new ChannelIdentity();
        when(identities.findByCorpIdAndAccessTypeAndChannelIdentity("corp-1", "WECOM_CUSTOMER_CONTACT", "ext-1"))
                .thenReturn(Optional.of(unbound));

        assertThat(service.findBound("corp-1", "WECOM_CUSTOMER_CONTACT", "ext-1")).isEmpty();
        assertThat(service.findBound("corp-2", "WECOM_CUSTOMER_CONTACT", "ext-1")).isEmpty();
    }

    @Test
    void findBoundReturnsBindingWhenCustomerBound() {
        ChannelIdentity bound = new ChannelIdentity();
        bound.setCustomerId(42L);
        when(identities.findByCorpIdAndAccessTypeAndChannelIdentity("corp-1", "WECOM_CUSTOMER_CONTACT", "ext-1"))
                .thenReturn(Optional.of(bound));

        assertThat(service.findBound("corp-1", "WECOM_CUSTOMER_CONTACT", "ext-1"))
                .map(ChannelIdentity::getCustomerId)
                .contains(42L);
    }
}

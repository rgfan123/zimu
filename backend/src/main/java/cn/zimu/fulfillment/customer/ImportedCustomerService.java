package cn.zimu.fulfillment.customer;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 来源订单导入客户：只按规范化姓名与手机号二元组确定性复用或创建。 */
@Service
public class ImportedCustomerService {

    private final JdbcTemplate jdbc;
    private final CustomerRepository customers;
    private final CustomerSourceRefRepository sourceRefs;
    private final CustomerCodeGenerator codes;

    public ImportedCustomerService(
            JdbcTemplate jdbc,
            CustomerRepository customers,
            CustomerSourceRefRepository sourceRefs,
            CustomerCodeGenerator codes) {
        this.jdbc = jdbc;
        this.customers = customers;
        this.sourceRefs = sourceRefs;
        this.codes = codes;
    }

    public CustomerInput resolve(SourceChannel channel, String rawName, String rawPhone) {
        ImportedCustomerIdentity identity = ImportedCustomerIdentity.from(rawName, rawPhone);
        String name = identity.normalizedName();
        String phone = identity.normalizedPhone();
        if (!identity.complete()) {
            return new CustomerInput(null, identity.sourceCustomerRef(), name.isBlank() ? "待匹配客户" : name);
        }
        String sourceRef = identity.sourceCustomerRef();
        List<ImportedCustomerIdentity> candidates = ImportedCustomerIdentity.lookupCandidates(rawName, rawPhone);
        candidates.stream()
                .map(ImportedCustomerIdentity::advisoryLockKey)
                .distinct()
                .sorted()
                .forEach(this::lockIdentity);

        Map<Long, Customer> matches = new LinkedHashMap<>();
        for (ImportedCustomerIdentity candidate : candidates) {
            Customer refMatch = findBySourceRef(channel, candidate.sourceCustomerRef());
            if (refMatch != null) matches.put(refMatch.getId(), refMatch);
            Customer profileMatch = findByIdentity(name, candidate.normalizedPhone());
            if (profileMatch != null) matches.put(profileMatch.getId(), profileMatch);
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("duplicate imported customer identity");
        }
        Customer customer = matches.isEmpty() ? null : matches.values().iterator().next();
        if (customer == null) {
            customer = codes.createBusinessCustomer(name);
            Map<String, Object> profile = new LinkedHashMap<>(customer.getProfile());
            profile.put("identity_name", name);
            profile.put("identity_phone", phone);
            profile.put("identity_source", "SOURCE_ORDER_IMPORT");
            customer.setProfile(profile);
            customer = customers.saveAndFlush(customer);
        }
        long customerId = customer.getId();
        sourceRefs.findBySourceChannelAndSourceCustomerRef(channel, sourceRef).orElseGet(() -> {
            CustomerSourceRef ref = new CustomerSourceRef();
            ref.setCustomerId(customerId);
            ref.setSourceChannel(channel);
            ref.setSourceCustomerRef(sourceRef);
            return sourceRefs.saveAndFlush(ref);
        });
        return new CustomerInput(customer.getCustomerCode(), sourceRef, customer.getCustomerName());
    }

    private void lockIdentity(String lockKey) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> {
                    resultSet.next();
                    return null;
                },
                lockKey);
    }

    private Customer findBySourceRef(SourceChannel channel, String sourceRef) {
        return sourceRefs.findBySourceChannelAndSourceCustomerRef(channel, sourceRef)
                .flatMap(ref -> customers.findById(ref.getCustomerId()))
                .orElse(null);
    }

    private Customer findByIdentity(String name, String phone) {
        List<Long> ids = jdbc.queryForList(
                """
                SELECT id FROM app.customers
                WHERE data_scope='BUSINESS' AND status='ACTIVE'
                  AND profile->>'identity_name'=?
                  AND profile->>'identity_phone'=?
                ORDER BY id LIMIT 2
                """,
                Long.class,
                name,
                phone);
        if (ids.size() > 1) {
            throw new IllegalStateException("duplicate imported customer identity");
        }
        return ids.isEmpty() ? null : customers.findById(ids.getFirst()).orElse(null);
    }

}

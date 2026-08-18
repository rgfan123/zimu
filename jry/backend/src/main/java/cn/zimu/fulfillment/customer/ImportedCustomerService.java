package cn.zimu.fulfillment.customer;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
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
        String name = normalizeName(rawName);
        String phone = normalizePhone(rawPhone);
        if (name.isBlank() || phone.isBlank()) {
            return new CustomerInput(null, "UNRESOLVED", name.isBlank() ? "待匹配客户" : name);
        }
        String identityHash = sha256(name + "\u001f" + phone);
        String sourceRef = "CONTACT-" + identityHash.substring(0, 32).toUpperCase();
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> {
                    resultSet.next();
                    return null;
                },
                "import-customer:" + identityHash);

        Customer customer = sourceRefs.findBySourceChannelAndSourceCustomerRef(channel, sourceRef)
                .flatMap(ref -> customers.findById(ref.getCustomerId()))
                .orElseGet(() -> findByIdentity(name, phone));
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

    private Customer findByIdentity(String name, String phone) {
        List<Long> ids = jdbc.queryForList(
                """
                SELECT id FROM app.customers
                WHERE data_scope='BUSINESS' AND status='ACTIVE'
                  AND profile->>'identity_name'=? AND profile->>'identity_phone'=?
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

    static String normalizeName(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
    }

    static String normalizePhone(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("[\\s\\-()]", "");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

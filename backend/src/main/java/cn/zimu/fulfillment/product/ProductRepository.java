package cn.zimu.fulfillment.product;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsByProductCode(String productCode);

    Optional<Product> findByProductCode(String productCode);

    /** 分配并发安全的内部商品编号；外部身份不得参与编号生成。 */
    @Query(value = "SELECT 'PROD-' || lpad(nextval('app.product_code_seq')::text, 6, '0')", nativeQuery = true)
    String nextProductCode();

    /** 全部已用商品标签去重候选，按字典序。 */
    @Query(value = """
            SELECT DISTINCT tag
            FROM app.products AS product
            CROSS JOIN LATERAL jsonb_array_elements_text(product.tags) AS tag
            WHERE product.tags IS NOT NULL
            ORDER BY tag
            """, nativeQuery = true)
    List<String> distinctTags();
}

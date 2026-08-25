package cn.zimu.fulfillment.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductBundleRepository extends JpaRepository<ProductBundle, Long> {

    boolean existsByBundleCode(String bundleCode);

    boolean existsByBarcode(String barcode);

    boolean existsByBarcodeAndIdNot(String barcode, long id);

    @Query("""
            SELECT b FROM ProductBundle b
            WHERE (:query IS NULL OR b.bundleCode LIKE %:query% OR b.bundleName LIKE %:query%)
            """)
    Page<ProductBundle> search(@Param("query") String query, Pageable pageable);
}

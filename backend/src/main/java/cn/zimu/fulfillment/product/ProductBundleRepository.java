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
            SELECT bundle FROM ProductBundle bundle
            WHERE lower(bundle.bundleCode) LIKE lower(:query)
               OR lower(bundle.bundleName) LIKE lower(:query)
            ORDER BY bundle.id
            """)
    Page<ProductBundle> search(@Param("query") String query, Pageable pageable);
}

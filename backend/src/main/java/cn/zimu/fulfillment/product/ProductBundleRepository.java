package cn.zimu.fulfillment.product;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductBundleRepository extends JpaRepository<ProductBundle, Long> {

    boolean existsByBundleCode(String bundleCode);

    boolean existsByBarcode(String barcode);
}

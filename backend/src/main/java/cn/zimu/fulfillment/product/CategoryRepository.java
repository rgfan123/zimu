package cn.zimu.fulfillment.product;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    boolean existsByCategoryCode(String categoryCode);

    Optional<Category> findByCategoryCode(String categoryCode);
}

package cn.zimu.fulfillment.customer;

import cn.zimu.fulfillment.common.domain.DataScope;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    boolean existsByCustomerCode(String customerCode);

    Optional<Customer> findByCustomerCode(String customerCode);

    Page<Customer> findByDataScope(DataScope dataScope, Pageable pageable);

    @Query(
            """
            select c from Customer c
            where c.dataScope = :dataScope
              and (lower(c.customerCode) like lower(concat('%', :query, '%'))
                or lower(c.customerName) like lower(concat('%', :query, '%')))
            """)
    Page<Customer> search(
            @Param("dataScope") DataScope dataScope,
            @Param("query") String query,
            Pageable pageable);
}

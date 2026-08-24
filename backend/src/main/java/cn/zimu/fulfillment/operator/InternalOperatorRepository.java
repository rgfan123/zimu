package cn.zimu.fulfillment.operator;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 内部运营人员仓储（Issue #89）。 */
public interface InternalOperatorRepository extends JpaRepository<InternalOperator, Long> {

    /** 企微 userid 全局唯一（含停用人员：同一 userid 永远只映射一个人）。 */
    boolean existsByWecomUserid(String wecomUserid);

    /** 解析 seam：按责任团队取 active 人员，登记顺序（id 升序）稳定返回。 */
    List<InternalOperator> findByResponsibleTeamAndActiveTrueOrderByIdAsc(String responsibleTeam);

    /** 列表检索：责任团队精确（已归一化）+ 姓名/企微 userid 模糊，可分别省略。 */
    @Query("""
            select o from InternalOperator o
            where (:team is null or o.responsibleTeam = :team)
              and (:query is null
                   or lower(o.displayName) like lower(concat('%', cast(:query as string), '%'))
                   or lower(coalesce(o.wecomUserid, '')) like lower(concat('%', cast(:query as string), '%')))
            """)
    Page<InternalOperator> search(@Param("team") String team, @Param("query") String query, Pageable pageable);
}

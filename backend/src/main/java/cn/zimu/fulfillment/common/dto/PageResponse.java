package cn.zimu.fulfillment.common.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** 统一分页响应：items / page / size / total_elements / total_pages。 */
public record PageResponse<T>(
        List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> items, Page<?> page) {
        return new PageResponse<>(
                items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}

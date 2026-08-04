package com.sprintiq.pms.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Set;

/**
 * Shared, validated paging/sorting binder used by every list endpoint.
 * Sort fields are validated against a per-endpoint allow-list, which keeps
 * arbitrary property paths (and the injection risk that comes with them) out of JPQL.
 */
@Getter
@Setter
@Schema(name = "PageQuery", description = "Common pagination, sorting and search parameters")
public class PageQuery {

    public static final int MAX_PAGE_SIZE = 100;

    @Min(0)
    private int page = 0;

    @Min(1)
    @Max(MAX_PAGE_SIZE)
    private int size = 20;

    /** Repeatable: {@code sort=createdAt,desc&sort=title,asc} */
    private List<String> sort = List.of();

    @Size(max = 200)
    private String q;

    public Pageable toPageable(Set<String> allowedSortFields, Sort defaultSort) {
        Sort resolved = SortParser.parse(sort, allowedSortFields, defaultSort);
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), resolved);
    }

    public boolean hasSearch() {
        return q != null && !q.isBlank();
    }

    public String searchTerm() {
        return hasSearch() ? "%" + q.trim().toLowerCase() + "%" : null;
    }
}

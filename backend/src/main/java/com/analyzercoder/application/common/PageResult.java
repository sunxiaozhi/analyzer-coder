package com.analyzercoder.application.common;

import com.github.pagehelper.PageInfo;
import java.util.List;
import java.util.function.Function;

/** 统一表示分页查询结果；页码从 1 开始，总数以满足当前过滤条件的记录为准。 */
public record PageResult<T>(List<T> items, int pageNum, int pageSize, long total, int pages) {
    public static <T> PageResult<T> fromPage(List<T> rows) {
        PageInfo<T> page = new PageInfo<>(rows);
        return new PageResult<>(
                List.copyOf(rows),
                page.getPageNum(),
                page.getPageSize(),
                page.getTotal(),
                page.getPages());
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(
                items.stream().map(mapper).toList(), pageNum, pageSize, total, pages);
    }

    public static void validate(int pageNum, int pageSize) {
        if (pageNum < 1) {
            throw new IllegalArgumentException("页码不能小于 1");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("每页数量必须在 1 到 100 之间");
        }
    }
}

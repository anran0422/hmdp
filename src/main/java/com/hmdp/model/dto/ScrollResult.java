package com.hmdp.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    /**
     * 便于查询其他数据 非 Blog
     */
    private List<?> list;

    /**
     * 最小时间
     */
    private Long minTime;

    /**
     * 偏移量
     */
    private Integer offset;
}

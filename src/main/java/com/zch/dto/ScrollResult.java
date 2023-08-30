package com.zch.dto;

import lombok.Data;

import java.util.List;

/**
 * @author Zch
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}

package io.stock.kr.calculator.stock.meta.crawling.tdd.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FSCStockPriceResponseBody {
    private Long numbOfRows;
    private Long pageNo;
    private Long totalCount;
    private FSCStockPriceItems items;
}
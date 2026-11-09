package ecommerce.dto.product;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Request for {@code POST /product/{id}/stock-adjustments}: a signed change, e.g. +50 received, -2 damaged. */
public class StockAdjustmentDto {

    @NotNull
    @Min(-1_000_000)
    @Max(1_000_000)
    private Integer delta;

    public Integer getDelta() {
        return delta;
    }

    public void setDelta(Integer delta) {
        this.delta = delta;
    }
}

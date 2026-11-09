package ecommerce.catalog;

import java.util.List;

import org.springframework.stereotype.Service;

import ecommerce.dto.product.ProductCardDto;
import ecommerce.dto.product.ProductPageDto;
import ecommerce.exceptions.BadRequestException;

/** Bounded catalog browsing for {@code GET /product/page}. */
@Service
public class CatalogService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final CatalogQueries queries;

    public CatalogService(CatalogQueries queries) {
        this.queries = queries;
    }

    /**
     * @param size requested page size; values above {@link #MAX_PAGE_SIZE} are capped, values below 1 rejected
     */
    public ProductPageDto page(Integer categoryId, String sortParam, Integer size, String cursorParam) {
        CatalogSort sort = CatalogSort.fromParam(sortParam);
        if (sort.byPrice() && categoryId == null) {
            // Without a category there is no index for price order, and the query would sort the whole table.
            throw new BadRequestException("sort=" + sort.param() + " requires categoryId");
        }
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageSize < 1) {
            throw new BadRequestException("size must be at least 1");
        }
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        CatalogCursor cursor = cursorParam == null || cursorParam.isBlank()
                ? null : CatalogCursor.decode(cursorParam, sort);

        // One extra row tells whether another page exists without a COUNT query.
        List<ProductCardDto> rows = queries.page(categoryId, sort, cursor, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<ProductCardDto> items = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = null;
        if (hasMore) {
            ProductCardDto last = items.get(items.size() - 1);
            nextCursor = new CatalogCursor(sort, last.priceCents(), last.id()).encode();
        }
        return new ProductPageDto(List.copyOf(items), nextCursor, pageSize, sort.param(), categoryId);
    }
}

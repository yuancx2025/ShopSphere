package ecommerce.catalog;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import ecommerce.exceptions.BadRequestException;

/**
 * Opaque keyset cursor: the sort key of the last item on the previous page. Encoded as URL-safe base64 of
 * {@code v1:<sort>:<priceCents>:<id>}. Clients pass it back unchanged; it is not a security boundary.
 */
public record CatalogCursor(CatalogSort sort, long priceCents, int id) {

    private static final String VERSION = "v1";

    public String encode() {
        String raw = VERSION + ":" + sort.param() + ":" + priceCents + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    public static CatalogCursor decode(String value, CatalogSort expectedSort) {
        String[] parts;
        try {
            parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII).split(":");
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("cursor is not valid");
        }
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new BadRequestException("cursor is not valid");
        }
        CatalogSort sort = CatalogSort.fromParam(parts[1]);
        if (sort != expectedSort) {
            throw new BadRequestException("cursor was issued for sort=" + sort.param());
        }
        try {
            return new CatalogCursor(sort, Long.parseLong(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException ex) {
            throw new BadRequestException("cursor is not valid");
        }
    }
}

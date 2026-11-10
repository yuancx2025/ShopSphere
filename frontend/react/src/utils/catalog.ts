import { useCallback, useEffect, useState } from 'react';
import axios from 'axios';
import { CatalogSort, Product, ProductPage } from '../types';
import { API_BASE_URL } from './constants';

export interface PageQuery {
  categoryId?: number;
  sort?: CatalogSort;
  size?: number;
}

/** One page of GET /product/page. Pass the previous page's nextCursor to continue. */
export async function fetchProductPage(query: PageQuery, cursor?: string | null): Promise<ProductPage> {
  const params: Record<string, string | number> = { size: query.size ?? 20, sort: query.sort ?? 'id' };
  if (query.categoryId !== undefined) params.categoryId = query.categoryId;
  if (cursor) params.cursor = cursor;
  const response = await axios.get<ProductPage>(`${API_BASE_URL}product/page`, { params });
  return response.data;
}

export async function fetchProduct(id: number): Promise<Product> {
  const response = await axios.get<Product>(`${API_BASE_URL}product/${id}`);
  return response.data;
}

/**
 * Loads the first page for a query and appends further pages on demand. The query is reset whenever
 * category, sort or size change, so a cursor is never reused across queries.
 */
export function useProductPages(query: PageQuery) {
  const { categoryId, sort, size } = query;
  const [items, setItems] = useState<Product[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchProductPage({ categoryId, sort, size })
      .then((page) => {
        if (cancelled) return;
        setItems(page.items);
        setNextCursor(page.nextCursor);
      })
      .catch((err) => {
        console.error(err);
        if (!cancelled) setError('Unable to load products.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [categoryId, sort, size]);

  const loadMore = useCallback(async () => {
    if (!nextCursor) return;
    setLoading(true);
    try {
      const page = await fetchProductPage({ categoryId, sort, size }, nextCursor);
      setItems((current) => [...current, ...page.items]);
      setNextCursor(page.nextCursor);
    } catch (err) {
      console.error(err);
      setError('Unable to load more products.');
    } finally {
      setLoading(false);
    }
  }, [categoryId, sort, size, nextCursor]);

  return { items, hasMore: nextCursor !== null, loading, error, loadMore };
}

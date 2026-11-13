import axios from 'axios';
import { SearchPage, SearchSort } from '../types';
import { API_BASE_URL } from './constants';

/** Same parameters and limits as GET /product/search (SearchQuery on the backend). */
export interface SearchParams {
  q: string;
  categoryId?: number;
  minPrice?: string;
  maxPrice?: string;
  sort?: SearchSort;
  page?: number;
  size?: number;
}

export const SEARCH_PAGE_SIZE = 20;
/** The backend stops at 1,000 results (page * size + size <= 1000). */
export const SEARCH_MAX_WINDOW = 1000;

export async function searchProducts(params: SearchParams): Promise<SearchPage> {
  const query: Record<string, string | number> = {
    q: params.q,
    sort: params.sort ?? 'relevance',
    page: params.page ?? 0,
    size: params.size ?? SEARCH_PAGE_SIZE,
  };
  if (params.categoryId !== undefined) query.categoryId = params.categoryId;
  if (params.minPrice) query.minPrice = params.minPrice;
  if (params.maxPrice) query.maxPrice = params.maxPrice;
  const response = await axios.get<SearchPage>(`${API_BASE_URL}product/search`, { params: query });
  return response.data;
}

/** Reads the search state from the URL, so results can be shared, bookmarked and reloaded. */
export function paramsFromUrl(search: URLSearchParams): SearchParams {
  const categoryId = search.get('categoryId');
  const sort = search.get('sort');
  return {
    q: search.get('q') ?? '',
    categoryId: categoryId ? Number(categoryId) : undefined,
    minPrice: search.get('minPrice') ?? undefined,
    maxPrice: search.get('maxPrice') ?? undefined,
    sort: sort === 'price_asc' || sort === 'price_desc' ? sort : 'relevance',
    page: Math.max(0, Number(search.get('page') ?? 0) || 0),
  };
}

export function paramsToUrl(params: SearchParams): URLSearchParams {
  const url = new URLSearchParams({ q: params.q });
  if (params.categoryId !== undefined) url.set('categoryId', String(params.categoryId));
  if (params.minPrice) url.set('minPrice', params.minPrice);
  if (params.maxPrice) url.set('maxPrice', params.maxPrice);
  if (params.sort && params.sort !== 'relevance') url.set('sort', params.sort);
  if (params.page) url.set('page', String(params.page));
  return url;
}

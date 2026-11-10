import React, { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import axios from 'axios';
import ProductBox from '../../components/product/ProductBox';
import { Category, SearchPage as SearchPageDto, SearchSort } from '../../types';
import {
  SEARCH_MAX_WINDOW,
  SEARCH_PAGE_SIZE,
  paramsFromUrl,
  paramsToUrl,
  searchProducts,
} from '../../utils/search';

interface Props {
  categories: Category[] | null;
}

/**
 * Keyword search with category, price and sort filters. All state lives in the URL (/search?q=...), and every
 * change runs one GET /product/search with the same parameters the backend validates.
 */
const SearchPage: React.FC<Props> = ({ categories }) => {
  const [urlParams, setUrlParams] = useSearchParams();
  const params = paramsFromUrl(urlParams);
  const [result, setResult] = useState<SearchPageDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Price inputs are applied on submit, not on every keystroke.
  const [minPrice, setMinPrice] = useState(params.minPrice ?? '');
  const [maxPrice, setMaxPrice] = useState(params.maxPrice ?? '');

  const key = urlParams.toString();
  useEffect(() => {
    const current = paramsFromUrl(new URLSearchParams(key));
    setMinPrice(current.minPrice ?? '');
    setMaxPrice(current.maxPrice ?? '');
    if (!current.q.trim()) {
      setResult(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    searchProducts(current)
      .then((page) => {
        if (!cancelled) setResult(page);
      })
      .catch((err) => {
        if (cancelled) return;
        const status = axios.isAxiosError(err) ? err.response?.status : undefined;
        const message = axios.isAxiosError(err) && typeof err.response?.data === 'string' ? err.response.data : null;
        setError(
          status === 400 && message ? message
            : status === 503 ? 'Search is temporarily unavailable. Please try again shortly.'
            : 'Unable to search right now.',
        );
        setResult(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [key]);

  /** Any filter change starts again from the first page. */
  const update = (changes: Partial<typeof params>) => {
    setUrlParams(paramsToUrl({ ...params, page: 0, ...changes }));
  };

  const applyPrice = (event: React.FormEvent) => {
    event.preventDefault();
    update({ minPrice: minPrice.trim() || undefined, maxPrice: maxPrice.trim() || undefined });
  };

  const page = params.page ?? 0;
  const canGoNext = !!result?.hasMore && (page + 2) * SEARCH_PAGE_SIZE <= SEARCH_MAX_WINDOW;

  if (!params.q.trim()) {
    return <div className="container text-center p-5">Type a search in the bar above.</div>;
  }

  return (
    <div className="container">
      <div className="row pt-3">
        <div className="col-12">
          <h4>Results for “{params.q}”</h4>
        </div>
      </div>
      <div className="row align-items-end pb-2">
        <div className="col-md-4 col-12 pt-2">
          <label htmlFor="search-category" className="d-block small text-muted">
            Category
          </label>
          <select
            id="search-category"
            className="form-control"
            value={params.categoryId ?? ''}
            onChange={(e) => update({ categoryId: e.target.value ? Number(e.target.value) : undefined })}
          >
            <option value="">All categories</option>
            {(categories ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.categoryName}
              </option>
            ))}
          </select>
        </div>
        <form className="col-md-5 col-12 pt-2 d-flex align-items-end" onSubmit={applyPrice}>
          <div className="mr-2">
            <label htmlFor="search-min" className="d-block small text-muted">
              Min $
            </label>
            <input
              id="search-min"
              className="form-control"
              inputMode="decimal"
              value={minPrice}
              onChange={(e) => setMinPrice(e.target.value)}
            />
          </div>
          <div className="mr-2">
            <label htmlFor="search-max" className="d-block small text-muted">
              Max $
            </label>
            <input
              id="search-max"
              className="form-control"
              inputMode="decimal"
              value={maxPrice}
              onChange={(e) => setMaxPrice(e.target.value)}
            />
          </div>
          <button type="submit" className="btn btn-outline-secondary">
            Apply
          </button>
        </form>
        <div className="col-md-3 col-12 pt-2">
          <label htmlFor="search-sort" className="d-block small text-muted">
            Sort by
          </label>
          <select
            id="search-sort"
            className="form-control"
            value={params.sort}
            onChange={(e) => update({ sort: e.target.value as SearchSort })}
          >
            <option value="relevance">Best match</option>
            <option value="price_asc">Price: low to high</option>
            <option value="price_desc">Price: high to low</option>
          </select>
        </div>
      </div>
      <div className="row">
        {error && <div className="col-12 text-center p-3 text-danger">{error}</div>}
        {loading && <div className="col-12 text-center p-3">Searching...</div>}
        {!loading && !error && result && result.items.length === 0 && (
          <div className="col-12 text-center p-5">No products match your search.</div>
        )}
        {!loading &&
          result?.items.map((product) => (
            <div key={product.id} className="col-md-6 col-xl-4 col-12 pt-3 justify-content-around d-flex">
              <ProductBox product={product} />
            </div>
          ))}
      </div>
      {!loading && result && (page > 0 || canGoNext) && (
        <div className="row">
          <div className="col-12 text-center p-4">
            <button
              type="button"
              className="btn btn-outline-secondary mr-2"
              disabled={page === 0}
              onClick={() => setUrlParams(paramsToUrl({ ...params, page: page - 1 }))}
            >
              Previous
            </button>
            <span className="mx-2">Page {page + 1}</span>
            <button
              type="button"
              className="btn btn-outline-secondary ml-2"
              disabled={!canGoNext}
              onClick={() => setUrlParams(paramsToUrl({ ...params, page: page + 1 }))}
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default SearchPage;

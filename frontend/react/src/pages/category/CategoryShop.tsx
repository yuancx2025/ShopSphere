import React, { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import ProductBox from '../../components/product/ProductBox';
import { CatalogSort, Category } from '../../types';
import { useProductPages } from '../../utils/catalog';

interface Props {
  categories: Category[] | null;
  loading: boolean;
}

const PAGE_SIZE = 20;

const CategoryShop: React.FC<Props> = ({ categories, loading }) => {
  const { id } = useParams<{ id: string }>();
  const categoryId = Number(id);
  const [sort, setSort] = useState<CatalogSort>('id');
  const { items, hasMore, loading: pageLoading, error, loadMore } = useProductPages({
    categoryId,
    sort,
    size: PAGE_SIZE,
  });

  const category = useMemo(
    () => categories?.find((c) => c.id === categoryId) ?? null,
    [categories, categoryId],
  );

  if (loading || !categories) {
    return <div className="text-center p-5">Loading...</div>;
  }

  if (!category) {
    return <div className="text-center p-5">Category not found.</div>;
  }

  return (
    <div className="container">
      <div className="row">
        <div className="col-12 text-center">
          <h4 className="pt-3">{category.categoryName}</h4>
          <p className="text-muted">{category.description}</p>
          <label className="mr-2" htmlFor="sort">
            Sort by
          </label>
          <select id="sort" value={sort} onChange={(e) => setSort(e.target.value as CatalogSort)}>
            <option value="id">Default</option>
            <option value="price_asc">Price: low to high</option>
          </select>
        </div>
      </div>
      <div className="row">
        {error && <div className="col-12 text-center p-3 text-danger">{error}</div>}
        {!pageLoading && items.length === 0 && !error && (
          <div className="col-12 text-center p-5">No products in this category yet.</div>
        )}
        {items.map((product) => (
          <div key={product.id} className="col-md-6 col-xl-4 col-12 pt-3 justify-content-around d-flex">
            <ProductBox product={product} />
          </div>
        ))}
      </div>
      <div className="row">
        <div className="col-12 text-center p-4">
          {pageLoading && <span>Loading...</span>}
          {!pageLoading && hasMore && (
            <button type="button" className="btn btn-outline-secondary" onClick={loadMore}>
              Load more
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

export default CategoryShop;

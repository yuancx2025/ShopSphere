import React, { useMemo } from 'react';
import { useParams } from 'react-router-dom';
import ProductBox from '../../components/product/ProductBox';
import { Category, Product } from '../../types';

interface Props {
  products: Product[] | null;
  categories: Category[] | null;
  loading: boolean;
}

const CategoryShop: React.FC<Props> = ({ products, categories, loading }) => {
  const { id } = useParams<{ id: string }>();
  const categoryId = Number(id);

  const category = useMemo(
    () => categories?.find((c) => c.id === categoryId) ?? null,
    [categories, categoryId],
  );
  const items = useMemo(
    () => (products ?? []).filter((p) => p.categoryId === categoryId),
    [products, categoryId],
  );

  if (loading || !products || !categories) {
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
        </div>
      </div>
      <div className="row">
        {items.length === 0 && (
          <div className="col-12 text-center p-5">No products in this category yet.</div>
        )}
        {items.map((product) => (
          <div key={product.id} className="col-md-6 col-xl-4 col-12 pt-3 justify-content-around d-flex">
            <ProductBox product={product} />
          </div>
        ))}
      </div>
    </div>
  );
};

export default CategoryShop;

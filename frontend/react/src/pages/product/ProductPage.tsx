import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import ProductBox from '../../components/product/ProductBox';
import { useProductPages } from '../../utils/catalog';

const ProductPage: React.FC = () => {
  const location = useLocation();
  const onAdminPage = location.pathname.startsWith('/admin/product');
  const { items, hasMore, loading, error, loadMore } = useProductPages({ size: 20 });

  return (
    <div className="container">
      <div className="row">
        <div className="col-12 text-center">
          <h4 className="pt-3">Our Products</h4>
          {onAdminPage && (
            <Link id="add-product" to="/admin/product/add">
              <button className="btn">Add a new Product</button>
            </Link>
          )}
        </div>
      </div>
      <div className="row">
        {error && <div className="col-12 text-center p-3 text-danger">{error}</div>}
        {items.map((product) => (
          <div key={product.id} className="col-md-6 col-xl-4 col-12 pt-3  justify-content-around d-flex">
            <ProductBox product={product} />
          </div>
        ))}
      </div>
      <div className="row">
        <div className="col-12 text-center p-4">
          {loading && <span>Loading...</span>}
          {!loading && hasMore && (
            <button type="button" className="btn btn-outline-secondary" onClick={loadMore}>
              Load more
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

export default ProductPage;

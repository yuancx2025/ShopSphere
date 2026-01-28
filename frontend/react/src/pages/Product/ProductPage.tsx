import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import ProductBox from '../../components/Product/ProductBox';
import { Product } from '../../types';

interface Props {
  products: Product[] | null;
  loading: boolean;
}

const ProductPage: React.FC<Props> = ({ products, loading }) => {
  const location = useLocation();
  const onAdminPage = location.pathname.startsWith('/admin/product');

  if (loading || !products) return <div className="text-center p-5">Loading...</div>;

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
        {products.map((product) => (
          <div key={product.id} className="col-md-6 col-xl-4 col-12 pt-3  justify-content-around d-flex">
            <ProductBox product={product} />
          </div>
        ))}
      </div>
    </div>
  );
};

export default ProductPage;

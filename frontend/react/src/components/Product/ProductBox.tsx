import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Product } from '../../types';
import './ProductBox.css';

interface Props {
  product: Product;
}

const ProductBox: React.FC<Props> = ({ product }) => {
  const location = useLocation();
  const onAdminPage = location.pathname.startsWith('/admin/product');

  return (
    <div className="card h-100">
      <div className="embed-responsive embed-responsive-16by9">
        <img className="card-img-top embed-responsive-item" src={product.imageURL} alt="Product" />
      </div>
      <div className="card-body">
        <Link to={`/product/show/${product.id}`}>
          <h5 className="card-title">{product.name}</h5>
        </Link>
        <p className="card-text">
          <sup>$</sup>
          {product.price}
        </p>
        <p className="card-text font-italic">{product.description.substring(0, 65)}...</p>
        {onAdminPage && (
          <Link id="edit-product" to={`/admin/product/${product.id}`}>
            Edit
          </Link>
        )}
      </div>
    </div>
  );
};

export default ProductBox;

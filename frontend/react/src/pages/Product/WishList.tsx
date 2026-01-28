import React, { useEffect, useState } from 'react';
import axios from 'axios';
import ProductBox from '../../components/Product/ProductBox';
import { Product } from '../../types';

interface Props {
  baseURL: string;
}

const WishList: React.FC<Props> = ({ baseURL }) => {
  const [products, setProducts] = useState<Product[] | null>(null);
  const [token, setToken] = useState<string | null>(null);

  useEffect(() => {
    setToken(localStorage.getItem('token'));
  }, []);

  useEffect(() => {
    if (!token) return;
    axios
      .get<Product[]>(`${baseURL}wishlist/${token}`)
      .then((res) => setProducts(res.data))
      .catch((err) => console.error(err));
  }, [baseURL, token]);

  if (!token) return <div className="text-center p-5">Please sign in to view your wishlist.</div>;
  if (!products) return <div className="text-center p-5">Loading...</div>;

  return (
    <div className="container">
      <div className="row">
        <div className="col-12 text-center">
          <h4 className="pt-3">Your WishList</h4>
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

export default WishList;

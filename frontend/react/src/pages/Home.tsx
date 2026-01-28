import React, { useMemo } from 'react';
import CategoryBox from '../components/Category/CategoryBox';
import ProductBox from '../components/Product/ProductBox';
import { Category, Product } from '../types';
import './Home.css';

interface Props {
  products: Product[] | null;
  categories: Category[] | null;
  loading: boolean;
}

const Home: React.FC<Props> = ({ products, categories, loading }) => {
  const { categorySize, productSize } = useMemo(() => {
    const cSize = categories ? Math.min(6, categories.length) : 0;
    const pSize = products ? Math.min(8, products.length) : 0;
    return { categorySize: cSize, productSize: pSize };
  }, [categories, products]);

  if (loading || !products || !categories) {
    return <div className="text-center p-5">Loading...</div>;
  }

  return (
    <div id="home">
      <div id="background-div" className="page-holder bg-cover">
        <div className="container py-5">
          <header className="text-left text-white py-5">
            <h3 className="mb-4 rounded">
              <a href="#start-shopping" className="bg-white px-2 py-2 rounded" id="heading">
                Start Shopping
              </a>
            </h3>
            <p id="content" className="lead mb-0 bg-dark p-1 rounded">
              Simple Coding Market is for educational purposes only. It can be used by developers to learn about
              developing an ecommerce application complete with backend and frontend for Web and Android
            </p>
          </header>
        </div>
      </div>

      <div id="start-shopping" className="container">
        <div className="row">
          <div className="col-12 text-left">
            <h2 className="pt-3">Top Categories</h2>
          </div>
        </div>
        <div className="row">
          {Array.from({ length: categorySize }).map((_, index) => (
            <div key={index} className="col-md-6 col-xl-4 col-12 pt-3  justify-content-around d-flex">
              <CategoryBox category={categories[index]} />
            </div>
          ))}
        </div>
      </div>

      <hr />
      <div className="container">
        <div className="row">
          <div className="col-12 text-left">
            <h2 className="pt-3">Top Products</h2>
          </div>
        </div>
        <div className="row">
          {Array.from({ length: productSize }).map((_, index) => (
            <div key={index} className="col-md-6 col-xl-4 col-12 pt-3  justify-content-around d-flex">
              <ProductBox product={products[index]} />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

export default Home;

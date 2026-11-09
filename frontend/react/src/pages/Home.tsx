import React from 'react';
import CategoryBox from '../components/category/CategoryBox';
import ProductBox from '../components/product/ProductBox';
import { Category } from '../types';
import { useProductPages } from '../utils/catalog';
import './Home.css';

interface Props {
  categories: Category[] | null;
  loading: boolean;
}

const Home: React.FC<Props> = ({ categories, loading }) => {
  // Eight products is all the home page shows, so that is all it asks for.
  const { items: products, loading: productsLoading } = useProductPages({ size: 8 });
  const categorySize = categories ? Math.min(6, categories.length) : 0;
  const productSize = products.length;

  if (loading || productsLoading || !categories) {
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

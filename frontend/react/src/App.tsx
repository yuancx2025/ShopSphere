import React, { useCallback, useEffect, useState } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import axios from 'axios';

import { Navbar } from './components/layout/Navbar';
import { Footer } from './components/layout/Footer';
import { RequireAdmin, RequireAuth } from './components/auth/RouteGuards';

import Home from './pages/Home';
import CategoryPage from './pages/Category/CategoryPage';
import AddCategory from './pages/Category/AddCategory';
import EditCategory from './pages/Category/EditCategory';
import ProductPage from './pages/Product/ProductPage';
import AddProduct from './pages/Product/AddProduct';
import EditProduct from './pages/Product/EditProduct';
import ShowDetails from './pages/Product/ShowDetails';
import Signup from './pages/Signup';
import Signin from './pages/Signin';
import WishList from './pages/Product/WishList';
import Cart from './pages/cart/Cart';
import Checkout from './pages/Checkout/Checkout';
import PaymentSuccess from './pages/payment/Success';
import PaymentFailed from './pages/payment/Failed';
import OrderHistory from './pages/order/OrderHistory';
import OrderDetails from './pages/order/OrderDetails';

import { Category, Product } from './types';
import { API_BASE_URL } from './utils/constants';

const baseURL = API_BASE_URL;

const App: React.FC = () => {
  const [products, setProducts] = useState<Product[] | null>(null);
  const [categories, setCategories] = useState<Category[] | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchData = useCallback(async () => {
    try {
      setLoading(true);
      const [prodRes, catRes] = await Promise.all([
        axios.get<Product[]>(`${baseURL}product/`),
        axios.get<Category[]>(`${baseURL}category/`),
      ]);
      setProducts(prodRes.data);
      setCategories(catRes.data);
    } catch (error) {
      console.error('Failed to load products/categories', error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  return (
    <div id="app">
      <Navbar />
      <div style={{ minHeight: '60vh' }}>
        <Routes>
          <Route
            path="/"
            element={<Home products={products} categories={categories} loading={loading} />}
          />
          <Route
            path="/admin/category/add"
            element={
              <RequireAdmin>
                <AddCategory baseURL={baseURL} fetchData={fetchData} />
              </RequireAdmin>
            }
          />
          <Route
            path="/admin/category"
            element={
              <RequireAdmin>
                <CategoryPage categories={categories} loading={loading} />
              </RequireAdmin>
            }
          />
          <Route
            path="/admin/category/:id"
            element={
              <RequireAdmin>
                <EditCategory
                  baseURL={baseURL}
                  categories={categories}
                  fetchData={fetchData}
                  loading={loading}
                />
              </RequireAdmin>
            }
          />
          <Route
            path="/admin/product/add"
            element={
              <RequireAdmin>
                <AddProduct
                  baseURL={baseURL}
                  categories={categories}
                  fetchData={fetchData}
                  loading={loading}
                />
              </RequireAdmin>
            }
          />
          <Route
            path="/admin/product"
            element={
              <RequireAdmin>
                <ProductPage products={products} loading={loading} />
              </RequireAdmin>
            }
          />
          <Route
            path="/admin/product/:id"
            element={
              <RequireAdmin>
                <EditProduct
                  baseURL={baseURL}
                  products={products}
                  categories={categories}
                  fetchData={fetchData}
                  loading={loading}
                />
              </RequireAdmin>
            }
          />
          <Route
            path="/product/show/:id"
            element={
              <ShowDetails
                baseURL={baseURL}
                products={products}
                categories={categories}
                loading={loading}
              />
            }
          />
          <Route path="/signup" element={<Signup baseURL={baseURL} />} />
          <Route path="/signin" element={<Signin baseURL={baseURL} />} />
          <Route
            path="/wishlist"
            element={
              <RequireAuth>
                <WishList baseURL={baseURL} />
              </RequireAuth>
            }
          />
          <Route
            path="/cart"
            element={
              <RequireAuth>
                <Cart baseURL={baseURL} />
              </RequireAuth>
            }
          />
          <Route
            path="/checkout"
            element={
              <RequireAuth>
                <Checkout baseURL={baseURL} />
              </RequireAuth>
            }
          />
          <Route
            path="/payment/success"
            element={
              <RequireAuth>
                <PaymentSuccess baseURL={baseURL} />
              </RequireAuth>
            }
          />
          <Route path="/payment/failed" element={<PaymentFailed />} />
          <Route
            path="/orders"
            element={
              <RequireAuth>
                <OrderHistory baseURL={baseURL} />
              </RequireAuth>
            }
          />
          <Route
            path="/order/:id"
            element={
              <RequireAuth>
                <OrderDetails baseURL={baseURL} />
              </RequireAuth>
            }
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </div>
      <Footer />
    </div>
  );
};

export default App;

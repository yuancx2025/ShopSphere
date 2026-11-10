import React, { useCallback, useEffect, useState } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import axios from 'axios';

import { Navbar } from './components/layout/Navbar';
import { Footer } from './components/layout/Footer';
import { RequireAdmin, RequireAuth } from './components/auth/RouteGuards';

import Home from './pages/Home';
import CategoryPage from './pages/category/CategoryPage';
import CategoryShop from './pages/category/CategoryShop';
import AddCategory from './pages/category/AddCategory';
import EditCategory from './pages/category/EditCategory';
import ProductPage from './pages/product/ProductPage';
import AddProduct from './pages/product/AddProduct';
import EditProduct from './pages/product/EditProduct';
import ShowDetails from './pages/product/ShowDetails';
import Signup from './pages/Signup';
import Signin from './pages/Signin';
import WishList from './pages/product/WishList';
import Cart from './pages/cart/Cart';
import Checkout from './pages/checkout/Checkout';
import PaymentSuccess from './pages/checkout/Success';
import PaymentFailed from './pages/checkout/Failed';
import OrderHistory from './pages/order/OrderHistory';
import OrderDetails from './pages/order/OrderDetails';
import SearchPage from './pages/search/SearchPage';

import { Category } from './types';
import { API_BASE_URL } from './utils/constants';

const baseURL = API_BASE_URL;

const App: React.FC = () => {
  // Products are no longer loaded up front: each page fetches a bounded slice from /product/page.
  const [categories, setCategories] = useState<Category[] | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchData = useCallback(async () => {
    try {
      setLoading(true);
      const catRes = await axios.get<Category[]>(`${baseURL}category/`);
      setCategories(catRes.data);
    } catch (error) {
      console.error('Failed to load categories', error);
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
            element={<Home categories={categories} loading={loading} />}
          />
          <Route path="/search" element={<SearchPage categories={categories} />} />
          <Route
            path="/category/:id"
            element={
              <CategoryShop categories={categories} loading={loading} />
            }
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
                <ProductPage />
              </RequireAdmin>
            }
          />
          <Route
            path="/admin/product/:id"
            element={
              <RequireAdmin>
                <EditProduct baseURL={baseURL} categories={categories} loading={loading} />
              </RequireAdmin>
            }
          />
          <Route
            path="/product/show/:id"
            element={
              <ShowDetails baseURL={baseURL} categories={categories} loading={loading} />
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

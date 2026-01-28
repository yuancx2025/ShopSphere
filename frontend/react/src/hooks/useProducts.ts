import { useState, useEffect, useCallback } from 'react';
import productService from '../api/services/productService';
import { Product, Category } from '../types';

export interface UseProductsReturn {
  products: Product[] | null;
  categories: Category[] | null;
  isLoading: boolean;
  error: string | null;
  fetchData: () => Promise<void>;
  getProductById: (id: number) => Product | undefined;
  getCategoryById: (id: number) => Category | undefined;
  getProductsByCategory: (categoryId: number) => Product[];
}

export const useProducts = (): UseProductsReturn => {
  const [products, setProducts] = useState<Product[] | null>(null);
  const [categories, setCategories] = useState<Category[] | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchData = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    try {
      const [productsData, categoriesData] = await Promise.all([
        productService.getAllProducts(),
        productService.getAllCategories(),
      ]);
      setProducts(productsData);
      setCategories(categoriesData);
    } catch (err) {
      setError('Failed to load products and categories');
      console.error(err);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const getProductById = useCallback(
    (id: number): Product | undefined => {
      return products?.find((p) => p.id === id);
    },
    [products]
  );

  const getCategoryById = useCallback(
    (id: number): Category | undefined => {
      return categories?.find((c) => c.id === id);
    },
    [categories]
  );

  const getProductsByCategory = useCallback(
    (categoryId: number): Product[] => {
      return products?.filter((p) => p.categoryId === categoryId) || [];
    },
    [products]
  );

  return {
    products,
    categories,
    isLoading,
    error,
    fetchData,
    getProductById,
    getCategoryById,
    getProductsByCategory,
  };
};

export default useProducts;

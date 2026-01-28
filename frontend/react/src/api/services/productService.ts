import { api } from '../client';
import { ENDPOINTS } from '../endpoints';
import { Product, Category } from '../../types';

export interface ProductDto {
  id?: number;
  name: string;
  description: string;
  imageURL: string;
  price: number;
  categoryId: number;
}

export interface ApiResponse {
  success: boolean;
  message: string;
}

export const productService = {
  /**
   * Get all products
   */
  getAllProducts: async (): Promise<Product[]> => {
    const response = await api.get<Product[]>(ENDPOINTS.PRODUCT.LIST);
    return response.data;
  },

  /**
   * Get product by ID
   */
  getProductById: async (id: number): Promise<Product> => {
    const response = await api.get<Product>(ENDPOINTS.PRODUCT.GET_BY_ID(id));
    return response.data;
  },

  /**
   * Add a new product
   */
  addProduct: async (product: ProductDto): Promise<ApiResponse> => {
    const response = await api.post<ApiResponse>(ENDPOINTS.PRODUCT.ADD, product);
    return response.data;
  },

  /**
   * Update an existing product
   */
  updateProduct: async (id: number, product: ProductDto): Promise<ApiResponse> => {
    const response = await api.post<ApiResponse>(ENDPOINTS.PRODUCT.UPDATE(id), product);
    return response.data;
  },

  /**
   * Get all categories
   */
  getAllCategories: async (): Promise<Category[]> => {
    const response = await api.get<Category[]>(ENDPOINTS.CATEGORY.LIST);
    return response.data;
  },

  /**
   * Add a new category
   */
  addCategory: async (category: Omit<Category, 'id'>): Promise<ApiResponse> => {
    const response = await api.post<ApiResponse>(ENDPOINTS.CATEGORY.CREATE, category);
    return response.data;
  },

  /**
   * Update an existing category
   */
  updateCategory: async (id: number, category: Omit<Category, 'id'>): Promise<ApiResponse> => {
    const response = await api.post<ApiResponse>(ENDPOINTS.CATEGORY.UPDATE(id), category);
    return response.data;
  },
};

export default productService;

import { api } from '../client';
import { ENDPOINTS } from '../endpoints';
import { CartItem, CartResponse } from '../../types';

export interface AddToCartData {
  productId: number;
  quantity: number;
}

export interface ApiResponse {
  success: boolean;
  message: string;
}

export const cartService = {
  /**
   * Get the current user's cart
   */
  getCart: async (token: string): Promise<CartResponse> => {
    const response = await api.get<CartResponse>(ENDPOINTS.CART.GET, {
      params: { token },
    });
    return response.data;
  },

  /**
   * Add an item to the cart
   */
  addToCart: async (data: AddToCartData, token: string): Promise<ApiResponse> => {
    const response = await api.post<ApiResponse>(
      ENDPOINTS.CART.ADD,
      data,
      { params: { token } }
    );
    return response.data;
  },

  /**
   * Update cart item quantity
   */
  updateCartItem: async (
    cartItemId: number,
    quantity: number,
    token: string
  ): Promise<ApiResponse> => {
    const response = await api.post<ApiResponse>(
      ENDPOINTS.CART.UPDATE(cartItemId),
      { quantity },
      { params: { token } }
    );
    return response.data;
  },

  /**
   * Remove item from cart
   */
  removeFromCart: async (cartItemId: number, token: string): Promise<ApiResponse> => {
    const response = await api.delete<ApiResponse>(
      ENDPOINTS.CART.DELETE(cartItemId),
      { params: { token } }
    );
    return response.data;
  },

  /**
   * Calculate total cost of items
   */
  calculateTotal: (items: CartItem[]): number => {
    return items.reduce((total, item) => total + item.product.price * item.quantity, 0);
  },

  /**
   * Get cart item count
   */
  getItemCount: (items: CartItem[]): number => {
    return items.reduce((count, item) => count + item.quantity, 0);
  },
};

export default cartService;

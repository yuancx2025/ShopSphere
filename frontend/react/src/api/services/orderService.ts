import { api } from '../client';
import { ENDPOINTS } from '../endpoints';
import { Order } from '../../types';

export interface CreateOrderData {
  sessionId: string;
}

export interface ApiResponse {
  success: boolean;
  message: string;
}

export interface CheckoutSession {
  sessionId: string;
}

export const orderService = {
  /**
   * Get user's order history
   */
  getOrderHistory: async (token: string): Promise<Order[]> => {
    const response = await api.get<Order[]>(ENDPOINTS.ORDER.LIST, {
      params: { token },
    });
    return response.data;
  },

  /**
   * Get order details by ID
   */
  getOrderById: async (orderId: number, token: string): Promise<Order> => {
    const response = await api.get<Order>(ENDPOINTS.ORDER.GET_BY_ID(orderId), {
      params: { token },
    });
    return response.data;
  },

  /**
   * Create a new order
   */
  createOrder: async (data: CreateOrderData, token: string): Promise<ApiResponse> => {
    const response = await api.post<ApiResponse>(
      ENDPOINTS.ORDER.ADD,
      data,
      { params: { token } }
    );
    return response.data;
  },

  /**
   * Create a Stripe checkout session
   */
  createCheckoutSession: async (token: string): Promise<CheckoutSession> => {
    const response = await api.post<CheckoutSession>(
      ENDPOINTS.CHECKOUT.CREATE_SESSION,
      {},
      { params: { token } }
    );
    return response.data;
  },

  /**
   * Format order date for display
   */
  formatOrderDate: (dateString: string): string => {
    const date = new Date(dateString);
    return date.toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  },
};

export default orderService;

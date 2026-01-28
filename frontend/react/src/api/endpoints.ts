// API Endpoints Configuration
// Centralized location for all API endpoint definitions

export const ENDPOINTS = {
  // Authentication
  AUTH: {
    SIGN_UP: '/user/signUp',
    SIGN_IN: '/user/signIn',
  },

  // Categories
  CATEGORY: {
    LIST: '/category/',
    CREATE: '/category/create',
    UPDATE: (id: number) => `/category/update/${id}`,
  },

  // Products
  PRODUCT: {
    LIST: '/product/',
    ADD: '/product/add',
    UPDATE: (id: number) => `/product/update/${id}`,
    GET_BY_ID: (id: number) => `/product/${id}`,
  },

  // Cart
  CART: {
    GET: '/cart/',
    ADD: '/cart/add',
    UPDATE: (id: number) => `/cart/update/${id}`,
    DELETE: (id: number) => `/cart/delete/${id}`,
  },

  // Wishlist
  WISHLIST: {
    GET: (token: string) => `/wishlist/${token}`,
    ADD: '/wishlist/add',
  },

  // Orders
  ORDER: {
    LIST: '/order/',
    ADD: '/order/add',
    GET_BY_ID: (id: number) => `/order/${id}`,
  },

  // Checkout
  CHECKOUT: {
    CREATE_SESSION: '/checkout/create-checkout-session',
  },
} as const;

export default ENDPOINTS;

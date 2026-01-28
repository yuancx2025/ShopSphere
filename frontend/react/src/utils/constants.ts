// API Configuration
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/';

// Application Constants
export const APP_NAME = 'E-Commerce';
export const APP_VERSION = '1.0.0';

// Pagination
export const DEFAULT_PAGE_SIZE = 12;
export const MAX_PAGE_SIZE = 50;

// Image Placeholders
export const PLACEHOLDER_IMAGE = 'https://via.placeholder.com/300x200?text=No+Image';
export const PLACEHOLDER_AVATAR = 'https://via.placeholder.com/100x100?text=Avatar';

// Local Storage Keys
export const STORAGE_KEYS = {
  TOKEN: 'token',
  USER: 'user',
  CART: 'cart',
  THEME: 'theme',
} as const;

// Route Paths
export const ROUTES = {
  HOME: '/',
  SIGNIN: '/signin',
  SIGNUP: '/signup',
  CART: '/cart',
  CHECKOUT: '/checkout',
  WISHLIST: '/wishlist',
  ORDERS: '/orders',
  ORDER_DETAILS: '/order/:id',
  PRODUCT_DETAILS: '/product/show/:id',
  ADMIN: {
    CATEGORY: '/admin/category',
    CATEGORY_ADD: '/admin/category/add',
    CATEGORY_EDIT: '/admin/category/:id',
    PRODUCT: '/admin/product',
    PRODUCT_ADD: '/admin/product/add',
    PRODUCT_EDIT: '/admin/product/:id',
  },
  PAYMENT: {
    SUCCESS: '/payment/success',
    FAILED: '/payment/failed',
  },
} as const;

// Validation Rules
export const VALIDATION = {
  EMAIL_PATTERN: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
  PASSWORD_MIN_LENGTH: 6,
  NAME_MIN_LENGTH: 2,
  NAME_MAX_LENGTH: 50,
  DESCRIPTION_MAX_LENGTH: 500,
} as const;

// HTTP Status Codes
export const HTTP_STATUS = {
  OK: 200,
  CREATED: 201,
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  INTERNAL_SERVER_ERROR: 500,
} as const;

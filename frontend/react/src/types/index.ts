// Category Types
export interface Category {
  id: number;
  categoryName: string;
  description: string;
  imageUrl?: string;
  imageURL?: string;
}

// Product Types
export interface Product {
  id: number;
  categoryId: number;
  name: string;
  description: string;
  imageURL: string;
  price: number;
}

// Cart Types
export interface CartItem {
  id?: number;
  quantity: number;
  product: Product;
}

export interface CartResponse {
  cartItems: CartItem[];
  totalCost: number;
}

// Order Types
export interface OrderItem {
  product: Product;
  quantity: number;
  price: number;
}

export interface Order {
  id: number;
  totalPrice: number;
  createdDate: string;
  orderItems: OrderItem[];
}

// User Types
export interface User {
  id: number;
  firstName: string;
  lastName: string;
  email: string;
}

// Auth Types
export interface AuthToken {
  token: string;
  user?: User;
}

// API Response Types
export interface ApiResponse {
  success: boolean;
  message: string;
}

// Pagination Types
export interface PaginatedResponse<T> {
  data: T[];
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
}

// Form Types
export interface SignInFormData {
  email: string;
  password: string;
}

export interface SignUpFormData {
  firstName: string;
  lastName: string;
  email: string;
  password: string;
  confirmPassword?: string;
}

export interface ProductFormData {
  name: string;
  description: string;
  imageURL: string;
  price: number;
  categoryId: number;
}

export interface CategoryFormData {
  categoryName: string;
  description: string;
  imageUrl: string;
}

// Wishlist Types
export interface WishlistItem {
  id: number;
  product: Product;
  createdDate: string;
}

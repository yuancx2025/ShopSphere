export interface Category {
  id: number;
  categoryName: string;
  description: string;
  imageUrl?: string;
  imageURL?: string;
}

export interface Product {
  id: number;
  categoryId: number;
  name: string;
  description: string;
  imageURL: string;
  price: number;
  stock: number;
}

export type OrderStatus = 'PENDING' | 'PAID' | 'CANCELLED' | 'FAILED';

export interface CartItem {
  id?: number;
  quantity: number;
  product: Product;
}

export interface CartResponse {
  cartItems: CartItem[];
  totalCost: number;
}

export interface OrderItem {
  productId?: number;
  productName: string;
  imageURL?: string;
  quantity: number;
  price: number;
  product?: Product;
}

export interface Order {
  id: number;
  totalPrice: number;
  createdDate: string;
  sessionId?: string;
  status: OrderStatus;
  orderItems: OrderItem[];
}

export interface CheckoutSession {
  sessionId: string;
  url: string;
}

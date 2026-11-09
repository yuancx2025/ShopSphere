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

export type CatalogSort = 'id' | 'price_asc';

/** GET /product/page response. nextCursor is null on the last page. */
export interface ProductPage {
  items: Product[];
  nextCursor: string | null;
  size: number;
  sort: CatalogSort;
  categoryId: number | null;
}

export interface StockAdjustmentResult {
  productId: number;
  delta: number;
  stock: number;
}

export type OrderStatus = 'PENDING' | 'PAID' | 'CANCELLED' | 'FAILED' | 'NEEDS_REVIEW';

export type ReviewReason =
  | 'ORDER_MISMATCH'
  | 'SESSION_MISMATCH'
  | 'AMOUNT_MISMATCH'
  | 'CURRENCY_MISMATCH'
  | 'PAID_AFTER_RELEASE';

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
  reviewReason?: ReviewReason | null;
  orderItems: OrderItem[];
}

export interface CheckoutSession {
  sessionId: string;
  url: string;
}

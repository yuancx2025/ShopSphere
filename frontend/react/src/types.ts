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
}

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

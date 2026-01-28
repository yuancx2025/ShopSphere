import React, { createContext, useContext, useState, useEffect, useCallback, ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import cartService, { AddToCartData } from '../api/services/cartService';
import { CartItem, CartResponse } from '../types';

interface CartContextType {
  cartItems: CartItem[];
  totalCost: number;
  itemCount: number;
  isLoading: boolean;
  error: string | null;
  addToCart: (data: AddToCartData) => Promise<void>;
  updateQuantity: (cartItemId: number, quantity: number) => Promise<void>;
  removeItem: (cartItemId: number) => Promise<void>;
  refreshCart: () => Promise<void>;
  clearCart: () => void;
}

const CartContext = createContext<CartContextType | undefined>(undefined);

interface CartProviderProps {
  children: ReactNode;
}

export const CartProvider: React.FC<CartProviderProps> = ({ children }) => {
  const [cartItems, setCartItems] = useState<CartItem[]>([]);
  const [totalCost, setTotalCost] = useState(0);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  const getToken = () => localStorage.getItem('token');

  const fetchCart = useCallback(async () => {
    const token = getToken();
    if (!token) return;

    setIsLoading(true);
    setError(null);

    try {
      const response: CartResponse = await cartService.getCart(token);
      setCartItems(response.cartItems);
      setTotalCost(response.totalCost);
    } catch (err) {
      setError('Failed to load cart');
      console.error(err);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchCart();
  }, [fetchCart]);

  const addToCart = async (data: AddToCartData) => {
    const token = getToken();
    if (!token) {
      navigate('/signin');
      return;
    }

    setIsLoading(true);
    try {
      await cartService.addToCart(data, token);
      await fetchCart();
    } catch (err) {
      setError('Failed to add item to cart');
      throw err;
    } finally {
      setIsLoading(false);
    }
  };

  const updateQuantity = async (cartItemId: number, quantity: number) => {
    const token = getToken();
    if (!token) return;

    setIsLoading(true);
    try {
      await cartService.updateCartItem(cartItemId, quantity, token);
      await fetchCart();
    } catch (err) {
      setError('Failed to update quantity');
      throw err;
    } finally {
      setIsLoading(false);
    }
  };

  const removeItem = async (cartItemId: number) => {
    const token = getToken();
    if (!token) return;

    setIsLoading(true);
    try {
      await cartService.removeFromCart(cartItemId, token);
      await fetchCart();
    } catch (err) {
      setError('Failed to remove item');
      throw err;
    } finally {
      setIsLoading(false);
    }
  };

  const clearCart = () => {
    setCartItems([]);
    setTotalCost(0);
  };

  const value: CartContextType = {
    cartItems,
    totalCost,
    itemCount: cartService.getItemCount(cartItems),
    isLoading,
    error,
    addToCart,
    updateQuantity,
    removeItem,
    refreshCart: fetchCart,
    clearCart,
  };

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
};

export const useCartContext = (): CartContextType => {
  const context = useContext(CartContext);
  if (context === undefined) {
    throw new Error('useCartContext must be used within a CartProvider');
  }
  return context;
};

export default CartContext;

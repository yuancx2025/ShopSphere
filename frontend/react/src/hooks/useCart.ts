import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import cartService, { AddToCartData } from '../api/services/cartService';
import { CartItem, CartResponse } from '../types';

export interface UseCartReturn {
  cartItems: CartItem[];
  totalCost: number;
  itemCount: number;
  isLoading: boolean;
  error: string | null;
  addToCart: (data: AddToCartData) => Promise<void>;
  updateQuantity: (cartItemId: number, quantity: number) => Promise<void>;
  removeItem: (cartItemId: number) => Promise<void>;
  refreshCart: () => Promise<void>;
  checkout: () => void;
}

export const useCart = (): UseCartReturn => {
  const [cartItems, setCartItems] = useState<CartItem[]>([]);
  const [totalCost, setTotalCost] = useState(0);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  const token = localStorage.getItem('token');

  const fetchCart = useCallback(async () => {
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
  }, [token]);

  useEffect(() => {
    fetchCart();
  }, [fetchCart]);

  const addToCart = useCallback(async (data: AddToCartData) => {
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
  }, [token, fetchCart, navigate]);

  const updateQuantity = useCallback(async (cartItemId: number, quantity: number) => {
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
  }, [token, fetchCart]);

  const removeItem = useCallback(async (cartItemId: number) => {
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
  }, [token, fetchCart]);

  const checkout = useCallback(() => {
    navigate('/checkout');
  }, [navigate]);

  return {
    cartItems,
    totalCost,
    itemCount: cartService.getItemCount(cartItems),
    isLoading,
    error,
    addToCart,
    updateQuantity,
    removeItem,
    refreshCart: fetchCart,
    checkout,
  };
};

export default useCart;

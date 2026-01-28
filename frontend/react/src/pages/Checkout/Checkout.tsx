import React, { useEffect, useState } from 'react';
import axios from 'axios';
import { CartItem, CartResponse } from '../../types';
import './Checkout.css';

declare global {
  interface Window {
    Stripe: any;
  }
}

interface Props {
  baseURL: string;
}

const Checkout: React.FC<Props> = ({ baseURL }) => {
  const [stripeAPIToken] = useState('<Stripe public key>');
  const [stripe, setStripe] = useState<any>(null);
  const [token, setToken] = useState<string | null>(null);
  const [checkoutBodyArray, setCheckoutBodyArray] = useState<any[]>([]);

  useEffect(() => {
    setToken(localStorage.getItem('token'));
  }, []);

  useEffect(() => {
    const includeStripe = (url: string, callback: () => void) => {
      const tag = document.createElement('script');
      tag.src = `//${url}`;
      tag.addEventListener('load', callback, false);
      document.body.appendChild(tag);
    };

    includeStripe('js.stripe.com/v3/', () => {
      if (window.Stripe) setStripe(window.Stripe(stripeAPIToken));
    });
  }, [stripeAPIToken]);

  useEffect(() => {
    if (!token) return;
    axios
      .get<CartResponse>(`${baseURL}cart/?token=${token}`)
      .then((response) => {
        const items: CartItem[] = response.data.cartItems;
        const body = items.map((item) => ({
          productName: item.product.name,
          quantity: item.quantity,
          price: item.product.price,
          productId: item.product.id,
        }));
        setCheckoutBodyArray(body);
      })
      .catch((err) => console.error(err));
  }, [baseURL, token]);

  const goToCheckout = () => {
    if (!stripe || checkoutBodyArray.length === 0) return;
    axios
      .post(`${baseURL}order/create-checkout-session`, checkoutBodyArray)
      .then((response) => {
        localStorage.setItem('sessionId', response.data.sessionId);
        return response.data;
      })
      .then((session) => stripe.redirectToCheckout({ sessionId: session.sessionId }));
  };

  if (!token) return <div className="text-center p-5">Please sign in to checkout.</div>;

  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div className="div_class">
        <h3>You will be redirected to payment page</h3>

        <div className="alert alert-primary" role="alert">
          While making payment use card number 4242 4242 4242 4242 and enter random cvv(3 digit)
        </div>

        <button
          className="btn checkout_button"
          id="proceed-to-checkout"
          onClick={goToCheckout}
          disabled={!stripe || checkoutBodyArray.length === 0}
        >
          Make payment
        </button>
      </div>
    </div>
  );
};

export default Checkout;

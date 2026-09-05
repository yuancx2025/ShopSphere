import React, { useEffect, useState } from 'react';
import axios from 'axios';
import { CartResponse, CheckoutSession } from '../../types';
import { authHeaders } from '../../utils/auth';
import './Checkout.css';

interface Props {
  baseURL: string;
}

const Checkout: React.FC<Props> = ({ baseURL }) => {
  const [token, setToken] = useState<string | null>(null);
  const [cartEmpty, setCartEmpty] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setToken(localStorage.getItem('token'));
  }, []);

  useEffect(() => {
    if (!token) return;
    axios
      .get<CartResponse>(`${baseURL}cart/`, { headers: authHeaders() })
      .then((response) => {
        setCartEmpty(!response.data.cartItems || response.data.cartItems.length === 0);
      })
      .catch((err) => console.error(err));
  }, [baseURL, token]);

  const goToCheckout = () => {
    if (cartEmpty || submitting) return;
    setSubmitting(true);
    setError(null);
    axios
      .post<CheckoutSession>(`${baseURL}order/create-checkout-session`, null, { headers: authHeaders() })
      .then((response) => {
        if (response.data.url) {
          window.location.href = response.data.url;
          return;
        }
        setError('Unable to start payment. Please try again.');
        setSubmitting(false);
      })
      .catch((err) => {
        const status = err.response?.status;
        const message = err.response?.data;
        if (status === 409) {
          setError(typeof message === 'string' ? message : 'Not enough stock for one or more items.');
        } else if (status === 400 && typeof message === 'string') {
          setError(message);
        } else {
          setError('Unable to start payment. Please try again.');
        }
        setSubmitting(false);
      });
  };

  if (!token) return <div className="text-center p-5">Please sign in to checkout.</div>;

  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div className="div_class">
        <h3>You will be redirected to payment page</h3>

        <div className="alert alert-primary" role="alert">
          <div>Test mode — use any future expiry date and any 3-digit CVC.</div>
          <ul className="mb-0">
            <li>Visa: 4242 4242 4242 4242</li>
            <li>Mastercard: 5555 5555 5555 4444</li>
            <li>Declined card: 4000 0000 0000 0002</li>
          </ul>
        </div>

        {error && (
          <div className="alert alert-danger" role="alert">
            {error}
          </div>
        )}

        <button
          className="btn checkout_button"
          id="proceed-to-checkout"
          onClick={goToCheckout}
          disabled={cartEmpty || submitting}
        >
          {submitting ? 'Redirecting...' : 'Make payment'}
        </button>
      </div>
    </div>
  );
};

export default Checkout;

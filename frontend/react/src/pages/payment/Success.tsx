import React, { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import axios from 'axios';
import { Order } from '../../types';
import { authHeaders } from '../../utils/auth';

interface Props {
  baseURL: string;
}

const POLL_INTERVAL_MS = 2000;
const MAX_ATTEMPTS = 15;

const PaymentSuccess: React.FC<Props> = ({ baseURL }) => {
  const [searchParams] = useSearchParams();
  const sessionId = searchParams.get('session_id');
  const token = localStorage.getItem('token');
  const [message, setMessage] = useState('Confirming your payment...');
  const [orderId, setOrderId] = useState<number | null>(null);
  const [needsSignIn, setNeedsSignIn] = useState(!token);

  useEffect(() => {
    if (!sessionId) {
      setMessage('Missing payment session. If you paid, your order will still be confirmed shortly.');
      return;
    }
    if (!token) {
      setNeedsSignIn(true);
      setMessage('Payment received. Sign in to see your order. If you already paid, the order is still being confirmed.');
      return;
    }

    let attempts = 0;
    let cancelled = false;

    const poll = () => {
      axios
        .get<Order>(`${baseURL}order/session/${encodeURIComponent(sessionId)}`, { headers: authHeaders() })
        .then((response) => {
          if (cancelled) return;
          if (response.data.status === 'PAID') {
            setOrderId(response.data.id);
            setMessage('Payment successful. Your order is confirmed.');
            return;
          }
          attempts += 1;
          if (attempts >= MAX_ATTEMPTS) {
            setMessage('Payment received, order is still confirming. You can check Order History in a moment.');
            return;
          }
          setTimeout(poll, POLL_INTERVAL_MS);
        })
        .catch((error) => {
          if (cancelled) return;
          if (error.response?.status === 401) {
            setNeedsSignIn(true);
            setMessage('Payment received. Sign in to see your order. The order is still being confirmed.');
            return;
          }
          attempts += 1;
          if (attempts >= MAX_ATTEMPTS) {
            setMessage('Payment received, order is still confirming. You can check Order History in a moment.');
            return;
          }
          setTimeout(poll, POLL_INTERVAL_MS);
        });
    };

    poll();
    return () => {
      cancelled = true;
    };
  }, [baseURL, sessionId, token]);

  return (
    <div className="container py-5">
      <div className="alert alert-success" role="alert" id="message">
        {message}
      </div>
      {needsSignIn && (
        <Link className="btn btn-primary" to="/signin">
          Sign in
        </Link>
      )}
      {orderId && (
        <Link className="btn btn-primary" to={`/order/${orderId}`}>
          View order
        </Link>
      )}
      {!orderId && !needsSignIn && (
        <Link className="btn btn-outline-secondary ml-2" to="/orders">
          Order history
        </Link>
      )}
    </div>
  );
};

export default PaymentSuccess;

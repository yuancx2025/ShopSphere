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
    const sessionURL = `${baseURL}order/session/${encodeURIComponent(sessionId)}`;

    const attempt = (first: boolean) => {
      // The first request asks the backend to confirm with Stripe in case the webhook is delayed.
      // Later ones only read the stored order, so polling costs nothing beyond a database lookup.
      const request = first
        ? axios.post<Order>(`${sessionURL}/reconcile`, null, { headers: authHeaders() })
        : axios.get<Order>(sessionURL, { headers: authHeaders() });

      request
        .then((response) => {
          if (cancelled) return;
          if (response.data.status === 'PAID') {
            setOrderId(response.data.id);
            setMessage('Payment successful. Your order is confirmed.');
            return;
          }
          if (response.data.status === 'NEEDS_REVIEW') {
            setOrderId(response.data.id);
            setMessage('Payment received. We need to review this order before confirming it; we will refund you if it cannot be fulfilled.');
            return;
          }
          retry();
        })
        .catch((error) => {
          if (cancelled) return;
          if (error.response?.status === 401) {
            setNeedsSignIn(true);
            setMessage('Payment received. Sign in to see your order. The order is still being confirmed.');
            return;
          }
          retry();
        });

      function retry() {
        attempts += 1;
        if (attempts >= MAX_ATTEMPTS) {
          setMessage('Payment received, order is still confirming. You can check Order History in a moment.');
          return;
        }
        setTimeout(() => attempt(false), POLL_INTERVAL_MS);
      }
    };

    attempt(true);
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

import React from 'react';
import { Link } from 'react-router-dom';

const PaymentFailed: React.FC = () => (
  <div className="container py-5">
    <div className="alert alert-danger" role="alert">
      Payment was cancelled or failed. Your cart is still available, and any held stock will be released when the checkout session expires.
    </div>
    <Link className="btn btn-primary" to="/cart">
      Return to cart
    </Link>
  </div>
);

export default PaymentFailed;

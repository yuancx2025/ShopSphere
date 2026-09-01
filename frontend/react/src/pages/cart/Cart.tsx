import React, { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import axios from 'axios';
import { CartItem, CartResponse } from '../../types';
import { authHeaders } from '../../utils/auth';
import './Cart.css';

interface Props {
  baseURL: string;
}

const Cart: React.FC<Props> = ({ baseURL }) => {
  const [cartItems, setCartItems] = useState<CartItem[]>([]);
  const [token, setToken] = useState<string | null>(null);
  const [totalCost, setTotalCost] = useState(0);
  const navigate = useNavigate();

  const loadCart = useCallback(() => {
    axios
      .get<CartResponse>(`${baseURL}cart/`, { headers: authHeaders() })
      .then((response) => {
        if (response.status === 200) {
          setCartItems(response.data.cartItems);
          setTotalCost(response.data.totalCost);
        }
      })
      .catch((error) => console.error(error));
  }, [baseURL]);

  useEffect(() => {
    setToken(localStorage.getItem('token'));
  }, []);

  useEffect(() => {
    if (!token) return;
    loadCart();
  }, [token, loadCart]);

  const updateQuantity = (cartItemId: number | undefined, quantity: number, maxStock: number) => {
    if (!cartItemId || quantity < 1) return;
    if (quantity > maxStock) {
      return;
    }
    axios
      .put(`${baseURL}cart/update/${cartItemId}`, { quantity }, { headers: authHeaders() })
      .then(() => loadCart())
      .catch((error) => {
        console.error(error);
        if (error.response?.status === 409) {
          loadCart();
        }
      });
  };

  const removeItem = (cartItemId: number | undefined) => {
    if (!cartItemId) return;
    axios
      .delete(`${baseURL}cart/delete/${cartItemId}`, { headers: authHeaders() })
      .then(() => loadCart())
      .catch((error) => console.error(error));
  };

  const isDisabled = () => cartItems.length === 0;

  if (!token) return <div className="text-center p-5">Please sign in to view your cart.</div>;

  return (
    <div className="container">
      <div className="row">
        <div className="col-12 text-center">
          <h3 className="pt-3">Shopping cart</h3>
        </div>
      </div>
      {cartItems.map((cartItem) => (
        <div key={cartItem.id ?? cartItem.product.id} className="row mt-2 pt-3 justify-content-around">
          <div className="col-2" />
          <div className="col-md-3 embed-responsive embed-responsive-16by9">
            <Link to={`/product/show/${cartItem.product.id}`}>
              <img src={cartItem.product.imageURL} className="w-100 card-img-top embed-responsive-item" alt="" />
            </Link>
          </div>
          <div className="col-md-5 px-3">
            <div className="card-block px-3">
              <h6 className="card-title">
                <Link to={`/product/show/${cartItem.product.id}`}>{cartItem.product.name}</Link>
              </h6>
              <p id="item-price" className="mb-0 font-weight-bold">
                $ {cartItem.product.price} per unit
              </p>
              <p id="item-quantity" className="mb-0">
                Quantity :
                <input
                  size={1}
                  className="p-0 h-25 border-bottom border-top-0 border-left-0 border-right-0"
                  type="number"
                  min={1}
                  max={cartItem.product.stock}
                  value={cartItem.quantity}
                  onChange={(e) => updateQuantity(cartItem.id, Number(e.target.value), cartItem.product.stock)}
                />
              </p>
              <p id="item-total-price" className="mb-0">
                Total : <span className="font-weight-bold"> $ {cartItem.product.price * cartItem.quantity}</span>
              </p>
              <button
                type="button"
                className="btn btn-sm btn-outline-danger mt-2"
                onClick={() => removeItem(cartItem.id)}
              >
                Remove
              </button>
            </div>
          </div>
          <div className="col-2" />
          <div className="col-12">
            <hr />
          </div>
        </div>
      ))}

      <div className="total-cost pt-2 text-right">
        <h5>Total : $ {totalCost}</h5>
        <button disabled={isDisabled()} type="button" className="btn btn-primary confirm" onClick={() => navigate('/checkout')}>
          Confirm Order
        </button>
      </div>
    </div>
  );
};

export default Cart;

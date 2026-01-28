import React, { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import axios from 'axios';
import { CartItem, CartResponse } from '../../types';
import './Cart.css';

interface Props {
  baseURL: string;
}

const Cart: React.FC<Props> = ({ baseURL }) => {
  const [cartItems, setCartItems] = useState<CartItem[]>([]);
  const [token, setToken] = useState<string | null>(null);
  const [totalCost, setTotalCost] = useState(0);
  const navigate = useNavigate();

  useEffect(() => {
    setToken(localStorage.getItem('token'));
  }, []);

  useEffect(() => {
    if (!token) return;
    axios
      .get<CartResponse>(`${baseURL}cart/?token=${token}`)
      .then((response) => {
        if (response.status === 200) {
          setCartItems(response.data.cartItems);
          setTotalCost(response.data.totalCost);
        }
      })
      .catch((error) => console.error(error));
  }, [baseURL, token]);

  const isDisabled = () => cartItems.length === 0;

  const checkout = () => {
    navigate('/checkout');
  };

  if (!token) return <div className="text-center p-5">Please sign in to view your cart.</div>;

  return (
    <div className="container">
      <div className="row">
        <div className="col-12 text-center">
          <h3 className="pt-3">Shopping cart</h3>
        </div>
      </div>
      {cartItems.map((cartItem) => (
        <div key={cartItem.product.id} className="row mt-2 pt-3 justify-content-around">
          <div className="col-2" />
          <div className="col-md-3 embed-responsive embed-responsive-16by9">
            <Link to={`/product/show/${cartItem.product.id}`}>
              <img src={cartItem.product.imageURL} className="w-100 card-img-top embed-responsive-item" />
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
                  value={cartItem.quantity}
                  readOnly
                />
              </p>
              <p id="item-total-price" className="mb-0">
                Total : <span className="font-weight-bold"> $ {cartItem.product.price * cartItem.quantity}</span>
              </p>
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
        <button disabled={isDisabled()} type="button" className="btn btn-primary confirm" onClick={checkout}>
          Confirm Order
        </button>
      </div>
    </div>
  );
};

export default Cart;

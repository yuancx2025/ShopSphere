import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import axios from 'axios';
import { Order, OrderItem } from '../../types';

interface Props {
  baseURL: string;
}

const OrderDetails: React.FC<Props> = ({ baseURL }) => {
  const { id } = useParams<{ id: string }>();
  const [order, setOrder] = useState<Order | null>(null);
  const [orderItems, setOrderItems] = useState<OrderItem[]>([]);
  const [token, setToken] = useState<string | null>(null);

  useEffect(() => {
    setToken(localStorage.getItem('token'));
  }, []);

  useEffect(() => {
    if (!token || !id) return;
    axios
      .get<Order>(`${baseURL}order/${id}?token=${token}`)
      .then((response) => {
        if (response.status === 200) {
          setOrder(response.data);
          setOrderItems(response.data.orderItems);
        }
      })
      .catch((err) => console.error(err));
  }, [baseURL, id, token]);

  if (!token) return <div className="text-center p-5">Please sign in to view the order.</div>;
  if (!order) return <div className="text-center p-5">Loading...</div>;

  return (
    <div className="container">
      <div className="row">
        <div className="col-12 text-center">
          <h4 className="pt-3">Order Id: {order.id}</h4>
        </div>
      </div>

      {orderItems.map((orderItem, index) => (
        <div key={index} className="row mt-2 pt-3 justify-content-around">
          <div className="col-1" />
          <div className="col-md-3 embed-responsive embed-responsive-16by9">
            <img src={orderItem.product.imageURL} className="w-100 card-img-top embed-responsive-item" />
            <hr />
          </div>
          <div className="col-md-5 px-3">
            <div className="card-block px-3">
              <h6 className="card-title">{orderItem.product.name}</h6>
              <p id="item-price" className="mb-0 font-weight-bold">
                ${orderItem.product.price} per unit
              </p>
              <p id="item-quantity" className="mb-0">Quantity : {orderItem.quantity}</p>
              <p id="item-total-price" className="mb-0">
                Total Price : <span className="font-weight-bold">${orderItem.price * orderItem.quantity}</span>
              </p>
            </div>
          </div>
          <div className="col-1" />
        </div>
      ))}

      <div className="total-cost pt-2 text-right">
        <h5>Total Cost : $ {order.totalPrice}</h5>
      </div>
    </div>
  );
};

export default OrderDetails;

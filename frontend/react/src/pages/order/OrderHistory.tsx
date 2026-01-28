import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import axios from 'axios';
import { Order } from '../../types';

interface Props {
  baseURL: string;
}

interface DisplayOrder {
  id: number;
  totalCost: number;
  orderdate: string;
  imageURL: string;
  totalItems: number;
}

const OrderHistory: React.FC<Props> = ({ baseURL }) => {
  const [token, setToken] = useState<string | null>(null);
  const [orderList, setOrderList] = useState<DisplayOrder[] | null>(null);

  useEffect(() => {
    setToken(localStorage.getItem('token'));
  }, []);

  useEffect(() => {
    if (!token) return;
    axios
      .get<Order[]>(`${baseURL}order/?token=${token}`)
      .then((response) => {
        if (response.status === 200) {
          const list: DisplayOrder[] = response.data.map((order) => ({
            id: order.id,
            totalCost: order.totalPrice,
            orderdate: order.createdDate.substring(0, 10),
            imageURL: order.orderItems[0]?.product.imageURL,
            totalItems: order.orderItems.length,
          }));
          setOrderList(list);
        }
      })
      .catch((error) => console.error(error));
  }, [baseURL, token]);

  if (!token) return <div className="text-center p-5">Please sign in to view orders.</div>;
  if (!orderList) return <div className="text-center p-5">Loading...</div>;

  return (
    <div className="container">
      <div className="row">
        <div className="col-12 text-center">
          <h4 className="pt-3">Your Orders</h4>
        </div>
      </div>
      {orderList.map((order) => (
        <div key={order.id} className="row mt-2 pt-3 justify-content-around">
          <div className="col-2" />
          <div className="col-md-3 embed-responsive embed-responsive-16by9">
            <img src={order.imageURL} className="w-100 card-img-top embed-responsive-item" />
          </div>
          <div className="col-md-5 px-3">
            <div className="card-block px-3">
              <h6 className="card-title">
                <Link to={`/order/${order.id}`}>Order No : {order.id}</Link>
              </h6>
              <p className="mb-0">
                {order.totalItems} item{order.totalItems > 1 ? 's' : ''}
              </p>
              <p id="item-price" className="mb-0 font-weight-bold">
                Total Cost : $ {order.totalCost}
              </p>
              <p id="item-total-price">Ordered on : {order.orderdate}</p>
            </div>
          </div>
          <div className="col-2" />
          <div className="col-12">
            <hr />
          </div>
        </div>
      ))}
    </div>
  );
};

export default OrderHistory;

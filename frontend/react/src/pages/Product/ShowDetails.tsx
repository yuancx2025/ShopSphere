import React, { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import axios from 'axios';
import swal from 'sweetalert';
import { Category, Product } from '../../types';
import './ShowDetails.css';

declare global {
  namespace JSX {
    interface IntrinsicElements {
      'ion-icon': React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement> & { name?: string }, HTMLElement>;
    }
  }
}

interface Props {
  baseURL: string;
  products: Product[] | null;
  categories: Category[] | null;
  loading: boolean;
}

const ShowDetails: React.FC<Props> = ({ baseURL, products, categories, loading }) => {
  const { id } = useParams<{ id: string }>();
  const [token, setToken] = useState<string | null>(null);
  const [quantity, setQuantity] = useState(1);

  useEffect(() => {
    setToken(localStorage.getItem('token'));
  }, []);

  const product = useMemo(() => {
    if (!products || !id) return null;
    return products.find((p) => p.id === Number(id)) || null;
  }, [products, id]);

  const category = useMemo(() => {
    if (!categories || !product) return null;
    return categories.find((c) => c.id === product.categoryId) || null;
  }, [categories, product]);

  const addToWishList = async (productId: number) => {
    try {
      await axios.post(`${baseURL}wishlist/add?token=${token}`, { id: productId });
      swal({ text: 'Added to WishList. Please continue', icon: 'success' });
    } catch (error) {
      console.error(error);
      swal({ text: 'Something wrong with add to wishlist', icon: 'error', closeOnClickOutside: false });
    }
  };

  const addToCart = async (productId: number) => {
    try {
      await axios.post(`${baseURL}cart/add?token=${token}`, {
        productId,
        quantity,
      });
      swal({ text: 'Product Added to the cart!', icon: 'success', closeOnClickOutside: false });
    } catch (error) {
      console.error(error);
      swal({ text: 'Something wrong with add to cart', icon: 'error', closeOnClickOutside: false });
    }
  };

  if (loading || !product || !category) return <div className="text-center p-5">Loading...</div>;

  return (
    <div className="container">
      <div className="row pt-5">
        <div className="col-md-1" />
        <div className="col-md-4 col-12">
          <img src={product.imageURL} alt={product.name} className="img-fluid" />
        </div>
        <div className="col-md-6 col-12 pt-3 pt-md-0">
          <h4>{product.name}</h4>
          <h6 className="category font-italic">{category.categoryName}</h6>
          <p>
            <span className="font-weight-bold">Description: -</span> <br />
            {product.description}
          </p>

          <div className="d-flex flex-row justify-content-between">
            <div className="input-group col-md-3 col-4 p-0">
              <div className="input-group-prepend">
                <span className="input-group-text" id="basic-addon1">
                  Quantity
                </span>
              </div>
              <input
                className="form-control"
                type="number"
                value={quantity}
                min={1}
                onChange={(e) => setQuantity(Number(e.target.value))}
              />
            </div>
            <button
              id="wishlist-button"
              className="btn mr-3 p-1 py-0"
              style={{ backgroundColor: '#b3a594' }}
              onClick={() => product && addToWishList(product.id)}
            >
              Add to wishlist
            </button>

            <button
              type="button"
              id="add-to-cart-button"
              className="btn"
              onClick={() => product && addToCart(product.id)}
            >
              Add to Cart
              <ion-icon name="cart-outline" />
            </button>
          </div>

          <div className="features pt-3">
            <h5>
              <strong>Features</strong>
            </h5>
            <ul>
              <li>Lorem ipsum dolor sit amet consectetur adipisicing elit.</li>
              <li>Officia quas, officiis eius magni error magnam voluptatem</li>
              <li>nesciunt quod! Earum voluptatibus quaerat dolorem doloribus</li>
              <li>molestias ipsum ab, ipsa consectetur laboriosam soluta et</li>
              <li>ut doloremque dolore corrupti, architecto iusto beatae.</li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ShowDetails;

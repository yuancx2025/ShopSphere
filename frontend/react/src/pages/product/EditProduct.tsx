import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import axios from 'axios';
import swal from 'sweetalert';
import { Category, StockAdjustmentResult } from '../../types';
import { authHeaders } from '../../utils/auth';
import { fetchProduct } from '../../utils/catalog';

interface Props {
  baseURL: string;
  categories: Category[] | null;
  loading: boolean;
}

/**
 * Edits a product's descriptive fields. Stock is not part of the form: saving the stock value the form loaded
 * would undo reservations made since. Stock changes are signed adjustments instead.
 */
const EditProduct: React.FC<Props> = ({ baseURL, categories, loading }) => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [loaded, setLoaded] = useState(false);
  const [categoryId, setCategoryId] = useState<number | undefined>();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [imageURL, setImageURL] = useState('');
  const [price, setPrice] = useState<number | undefined>();
  const [stock, setStock] = useState<number>(0);
  const [delta, setDelta] = useState<number>(0);

  useEffect(() => {
    if (!id) return;
    fetchProduct(Number(id))
      .then((product) => {
        setCategoryId(product.categoryId);
        setName(product.name);
        setDescription(product.description);
        setImageURL(product.imageURL);
        setPrice(product.price);
        setStock(product.stock ?? 0);
        setLoaded(true);
      })
      .catch((err) => console.error(err));
  }, [id]);

  const editProduct = async () => {
    if (!id || !categoryId || price === undefined) return;
    const updatedProduct = { categoryId, name, description, imageURL, price };
    try {
      await axios.post(`${baseURL}product/update/${id}`, updatedProduct, {
        headers: { 'Content-Type': 'application/json', ...authHeaders() },
      });
      navigate('/admin/product');
      swal({ text: 'Product Updated Successfully!', icon: 'success', closeOnClickOutside: false });
    } catch (err) {
      console.error(err);
      swal({ text: 'Unable to update product', icon: 'error', closeOnClickOutside: false });
    }
  };

  const adjustStock = async () => {
    if (!id || delta === 0) return;
    try {
      const response = await axios.post<StockAdjustmentResult>(
        `${baseURL}product/${id}/stock-adjustments`,
        { delta },
        { headers: { 'Content-Type': 'application/json', ...authHeaders() } },
      );
      setStock(response.data.stock);
      setDelta(0);
      swal({ text: `Stock is now ${response.data.stock}`, icon: 'success', closeOnClickOutside: false });
    } catch (err) {
      console.error(err);
      const data = axios.isAxiosError(err) ? err.response?.data : undefined;
      swal({
        text: typeof data === 'string' ? data : 'Unable to adjust stock',
        icon: 'error',
        closeOnClickOutside: false,
      });
    }
  };

  if (loading || !loaded || !categories) return <div className="text-center p-5">Loading...</div>;

  return (
    <div className="container">
      <div className="row">
        <div className="col-12 text-center">
          <h4 className="pt-3">Edit Product</h4>
        </div>
      </div>

      <div className="row">
        <div className="col-3" />
        <div className="col-md-6 px-5 px-md-0">
          <form onSubmit={(e) => e.preventDefault()}>
            <div className="form-group">
              <label>Category</label>
              <select
                className="form-control"
                value={categoryId ?? ''}
                onChange={(e) => setCategoryId(Number(e.target.value))}
                required
              >
                <option value="" disabled>
                  Choose...
                </option>
                {categories.map((category) => (
                  <option key={category.id} value={category.id}>
                    {category.categoryName}
                  </option>
                ))}
              </select>
            </div>
            <div className="form-group">
              <label>Name</label>
              <input type="text" className="form-control" value={name} onChange={(e) => setName(e.target.value)} required />
            </div>
            <div className="form-group">
              <label>Description</label>
              <input
                type="text"
                className="form-control"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                required
              />
            </div>
            <div className="form-group">
              <label>ImageURL</label>
              <input type="url" className="form-control" value={imageURL} onChange={(e) => setImageURL(e.target.value)} required />
            </div>
            <div className="form-group">
              <label>Price</label>
              <input
                type="number"
                step="0.01"
                min={0}
                className="form-control"
                value={price ?? ''}
                onChange={(e) => setPrice(Number(e.target.value))}
                required
              />
            </div>
            <button type="button" className="btn btn-primary" onClick={editProduct}>
              Submit
            </button>
          </form>

          <hr />
          <h5>Stock: {stock}</h5>
          <form onSubmit={(e) => e.preventDefault()}>
            <div className="form-group">
              <label>Adjust by (+ received, − damaged or lost)</label>
              <input type="number" className="form-control" value={delta} onChange={(e) => setDelta(Number(e.target.value))} />
            </div>
            <button
              type="button"
              className="btn btn-outline-primary"
              onClick={adjustStock}
              disabled={delta === 0}
            >
              Apply adjustment
            </button>
          </form>
        </div>
        <div className="col-3" />
      </div>
    </div>
  );
};

export default EditProduct;

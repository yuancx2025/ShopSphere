import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import axios from 'axios';
import swal from 'sweetalert';
import { Category, Product } from '../../types';

interface Props {
  baseURL: string;
  products: Product[] | null;
  categories: Category[] | null;
  fetchData: () => Promise<void>;
  loading: boolean;
}

const EditProduct: React.FC<Props> = ({ baseURL, products, categories, fetchData, loading }) => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [categoryId, setCategoryId] = useState<number | undefined>();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [imageURL, setImageURL] = useState('');
  const [price, setPrice] = useState<number | undefined>();

  const currentProduct = useMemo(() => {
    if (!products || !id) return null;
    return products.find((p) => p.id === Number(id)) || null;
  }, [products, id]);

  useEffect(() => {
    if (currentProduct) {
      setCategoryId(currentProduct.categoryId);
      setName(currentProduct.name);
      setDescription(currentProduct.description);
      setImageURL(currentProduct.imageURL);
      setPrice(currentProduct.price);
    }
  }, [currentProduct]);

  const editProduct = async () => {
    if (!id || !categoryId || price === undefined) return;
    const updatedProduct = {
      id: Number(id),
      categoryId,
      name,
      description,
      imageURL,
      price,
    };

    try {
      await axios.post(`${baseURL}product/update/${id}`, updatedProduct, {
        headers: { 'Content-Type': 'application/json' },
      });
      await fetchData();
      navigate('/admin/product');
      swal({
        text: 'Product Updated Successfully!',
        icon: 'success',
        closeOnClickOutside: false,
      });
    } catch (err) {
      console.error(err);
      swal({
        text: 'Unable to update product',
        icon: 'error',
        closeOnClickOutside: false,
      });
    }
  };

  if (loading || !currentProduct || !categories) return <div className="text-center p-5">Loading...</div>;

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
              <input
                type="text"
                className="form-control"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
              />
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
              <input
                type="url"
                className="form-control"
                value={imageURL}
                onChange={(e) => setImageURL(e.target.value)}
                required
              />
            </div>
            <div className="form-group">
              <label>Price</label>
              <input
                type="number"
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
        </div>
        <div className="col-3" />
      </div>
    </div>
  );
};

export default EditProduct;

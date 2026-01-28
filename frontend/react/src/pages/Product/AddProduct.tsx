import React, { useState } from 'react';
import axios from 'axios';
import swal from 'sweetalert';
import { Category } from '../../types';

interface Props {
  baseURL: string;
  categories: Category[] | null;
  fetchData: () => Promise<void>;
  loading: boolean;
}

const AddProduct: React.FC<Props> = ({ baseURL, categories, fetchData, loading }) => {
  const [categoryId, setCategoryId] = useState<number | undefined>();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [imageURL, setImageURL] = useState('');
  const [price, setPrice] = useState<number | undefined>();

  const addProduct = async () => {
    if (!categoryId || !price) return;
    const newProduct = {
      categoryId,
      name,
      description,
      imageURL,
      price,
    };

    try {
      await axios.post(`${baseURL}product/add`, newProduct, {
        headers: { 'Content-Type': 'application/json' },
      });
      await fetchData();
      swal({
        text: 'Product Added Successfully!',
        icon: 'success',
        closeOnClickOutside: false,
      });
      setName('');
      setDescription('');
      setImageURL('');
      setPrice(undefined);
    } catch (err) {
      console.error(err);
      swal({
        text: 'Unable to add product',
        icon: 'error',
        closeOnClickOutside: false,
      });
    }
  };

  if (loading || !categories) return <div className="text-center p-5">Loading...</div>;

  return (
    <div className="container">
      <div className="row">
        <div className="col-12 text-center">
          <h4 className="pt-3">Add new Product</h4>
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
            <button type="button" className="btn btn-primary" onClick={addProduct}>
              Submit
            </button>
          </form>
        </div>
        <div className="col-3" />
      </div>
    </div>
  );
};

export default AddProduct;

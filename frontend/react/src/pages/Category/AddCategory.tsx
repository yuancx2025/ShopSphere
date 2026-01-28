import React, { useState } from 'react';
import axios from 'axios';
import swal from 'sweetalert';

interface Props {
  baseURL: string;
  fetchData: () => Promise<void>;
}

const AddCategory: React.FC<Props> = ({ baseURL, fetchData }) => {
  const [categoryName, setCategoryName] = useState('');
  const [description, setDescription] = useState('');
  const [imageURL, setImageURL] = useState('');

  const addCategory = async () => {
    const newCategory = {
      categoryName,
      description,
      imageUrl: imageURL,
    };

    try {
      await axios.post(`${baseURL}category/create`, newCategory, {
        headers: { 'Content-Type': 'application/json' },
      });
      await fetchData();
      swal({
        text: 'Category Added Successfully!',
        icon: 'success',
        closeOnClickOutside: false,
      });
      setCategoryName('');
      setDescription('');
      setImageURL('');
    } catch (err) {
      console.error(err);
      swal({
        text: 'Unable to add category',
        icon: 'error',
        closeOnClickOutside: false,
      });
    }
  };

  return (
    <div className="container">
      <div className="row">
        <div className="col-12 text-center">
          <h4 className="pt-3">Add new Category</h4>
        </div>
      </div>

      <div className="row">
        <div className="col-3" />
        <div className="col-md-6 px-5 px-md-0">
          <form onSubmit={(e) => e.preventDefault()}>
            <div className="form-group">
              <label>Category Name</label>
              <input
                type="text"
                className="form-control"
                value={categoryName}
                onChange={(e) => setCategoryName(e.target.value)}
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
            <button type="button" className="btn btn-primary" onClick={addCategory}>
              Submit
            </button>
          </form>
        </div>
        <div className="col-3" />
      </div>
    </div>
  );
};

export default AddCategory;

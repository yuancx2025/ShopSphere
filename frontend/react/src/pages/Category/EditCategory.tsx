import React, { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import axios from 'axios';
import swal from 'sweetalert';
import { Category } from '../../types';

interface Props {
  baseURL: string;
  categories: Category[] | null;
  fetchData: () => Promise<void>;
  loading: boolean;
}

const EditCategory: React.FC<Props> = ({ baseURL, categories, fetchData, loading }) => {
  const { id } = useParams<{ id: string }>();
  const [categoryName, setCategoryName] = useState('');
  const [description, setDescription] = useState('');
  const [imageUrl, setImageUrl] = useState('');

  const currentCategory = useMemo(() => {
    if (!categories || !id) return null;
    return categories.find((c) => c.id === Number(id)) || null;
  }, [categories, id]);

  useEffect(() => {
    if (currentCategory) {
      setCategoryName(currentCategory.categoryName);
      setDescription(currentCategory.description);
      setImageUrl(currentCategory.imageUrl || currentCategory.imageURL || '');
    }
  }, [currentCategory]);

  const editCategory = async () => {
    if (!id) return;
    const updatedCategory = {
      id: Number(id),
      categoryName,
      description,
      imageUrl,
    };

    try {
      await axios.post(`${baseURL}category/update/${id}`, updatedCategory, {
        headers: { 'Content-Type': 'application/json' },
      });
      await fetchData();
      swal({
        text: 'Category Updated Successfully!',
        icon: 'success',
        closeOnClickOutside: false,
      });
    } catch (err) {
      console.error(err);
      swal({
        text: 'Unable to update category',
        icon: 'error',
        closeOnClickOutside: false,
      });
    }
  };

  if (loading || !currentCategory) return <div className="text-center p-5">Loading...</div>;

  return (
    <div className="container">
      <div className="row">
        <div className="col-12 text-center">
          <h4 className="pt-3">Edit Category</h4>
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
                value={imageUrl}
                onChange={(e) => setImageUrl(e.target.value)}
                required
              />
            </div>
            <button type="button" className="btn btn-primary" onClick={editCategory}>
              Submit
            </button>
          </form>
        </div>
        <div className="col-3" />
      </div>
    </div>
  );
};

export default EditCategory;

import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Category } from '../../types';
import './CategoryBox.css';

interface Props {
  category: Category;
}

const CategoryBox: React.FC<Props> = ({ category }) => {
  const location = useLocation();
  const onAdminPage = location.pathname.startsWith('/admin/category');
  const image = category.imageUrl || category.imageURL || '';
  return (
    <div className="card h-100">
      <Link to={`/category/${category.id}`}>
        <div className="embed-responsive embed-responsive-16by9">
          <img className="card-img-top embed-responsive-item" src={image} alt="Category" />
        </div>
      </Link>
      <div className="card-body">
        <Link to={`/category/${category.id}`}>
          <h5 className="card-title">{category.categoryName}</h5>
        </Link>
        <p className="card-text font-italic">
          {category.description.substring(0, 65)}...
        </p>
        {onAdminPage && (
          <Link id="edit-category" to={`/admin/category/${category.id}`}>
            Edit
          </Link>
        )}
      </div>
    </div>
  );
};

export default CategoryBox;

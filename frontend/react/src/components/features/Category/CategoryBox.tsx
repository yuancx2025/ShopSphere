import React from 'react';
import { Link } from 'react-router-dom';
import { Category } from '../../../types';
import './CategoryBox.css';

interface Props {
  category: Category;
}

const CategoryBox: React.FC<Props> = ({ category }) => {
  const image = category.imageUrl || category.imageURL || '';
  return (
    <div className="card h-100">
      <div className="embed-responsive embed-responsive-16by9">
        <img className="card-img-top embed-responsive-item" src={image} alt="Category" />
      </div>
      <div className="card-body">
        <h5 className="card-title">{category.categoryName}</h5>
        <p className="card-text font-italic">
          {category.description.substring(0, 65)}...
        </p>
        <Link id="edit-category" to={`/admin/category/${category.id}`}>
          Edit
        </Link>
      </div>
    </div>
  );
};

export default CategoryBox;

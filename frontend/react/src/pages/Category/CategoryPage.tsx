import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import CategoryBox from '../../components/Category/CategoryBox';
import { Category } from '../../types';

interface Props {
  categories: Category[] | null;
  loading: boolean;
}

const CategoryPage: React.FC<Props> = ({ categories, loading }) => {
  const location = useLocation();
  const onAdminPage = location.pathname.startsWith('/admin/category');

  if (loading || !categories) return <div className="text-center p-5">Loading...</div>;

  return (
    <div className="container">
      <div className="row">
        <div className="col-12 text-center">
          <h4 className="pt-3">Our Categories</h4>
          {onAdminPage && (
            <Link id="add-category" to="/admin/category/add">
              <button className="btn">Add a new Category</button>
            </Link>
          )}
        </div>
      </div>
      <div className="row">
        {categories.map((category) => (
          <div key={category.id} className="col-md-6 col-xl-4 col-12 pt-3  justify-content-around d-flex">
            <CategoryBox category={category} />
          </div>
        ))}
      </div>
    </div>
  );
};

export default CategoryPage;

import React, { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import swal from 'sweetalert';
import logo from '../assets/icon.png';
import './Navbar.css';

const Navbar: React.FC = () => {
  const [token, setToken] = useState<string | null>(null);
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    setToken(localStorage.getItem('token'));
  }, [location.pathname]);

  const signout = () => {
    localStorage.removeItem('token');
    setToken(null);
    navigate('/');
    swal({
      text: 'Logged you out. Visit Again',
      icon: 'success',
      closeOnClickOutside: false,
    });
  };

  return (
    <nav className="navbar navbar-expand-lg navbar-dark bg-dark">
      <Link className="navbar-brand" to="/">
        <img id="logo" src={logo} alt="Logo" />
      </Link>

      <button
        className="navbar-toggler"
        type="button"
        data-toggle="collapse"
        data-target="#navbarSupportedContent"
        aria-controls="navbarSupportedContent"
        aria-expanded="false"
        aria-label="Toggle navigation"
      >
        <span className="navbar-toggler-icon" />
      </button>

      <div className="collapse navbar-collapse" id="navbarSupportedContent">
        <form className="form-inline ml-auto mr-auto">
          <div className="input-group">
            <input
              size={100}
              type="text"
              className="form-control"
              placeholder="Search Items"
              aria-label="search"
            />
            <div className="input-group-prepend">
              <span className="input-group-text" id="search-button-navbar">
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  width="16"
                  height="16"
                  fill="currentColor"
                  className="bi bi-search"
                  viewBox="0 0 16 16"
                >
                  <path d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001c.03.04.062.078.098.115l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85a1.007 1.007 0 0 0-.115-.1zM12 6.5a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0z" />
                </svg>
              </span>
            </div>
          </div>
        </form>

        <li className="nav-item dropdown list-unstyled">
          <a
            className="nav-link text-light dropdown-toggle"
            href="#"
            id="navbarDropdownAdmin"
            role="button"
            data-toggle="dropdown"
            aria-haspopup="true"
            aria-expanded="false"
          >
            Admin
          </a>
          <div className="dropdown-menu" aria-labelledby="navbarDropdownAdmin">
            <Link className="dropdown-item" to="/admin/category">
              Category
            </Link>
            <Link className="dropdown-item" to="/admin/product">
              Products
            </Link>
          </div>
        </li>

        <li className="nav-item dropdown list-unstyled">
          <a
            className="nav-link text-light dropdown-toggle"
            href="#"
            id="navbarDropdown"
            role="button"
            data-toggle="dropdown"
            aria-haspopup="true"
            aria-expanded="false"
          >
            Accounts
          </a>
          <div className="dropdown-menu" aria-labelledby="navbarDropdown">
            {!token && (
              <Link className="dropdown-item" to="/signin">
                WishList
              </Link>
            )}
            {token && (
              <Link className="dropdown-item" to="/wishlist">
                WishList
              </Link>
            )}
            {token && (
              <Link className="dropdown-item" to="/orders">
                Order History
              </Link>
            )}
            {!token && (
              <Link className="dropdown-item" to="/signin">
                Log In
              </Link>
            )}
            {!token && (
              <Link className="dropdown-item" to="/signup">
                Sign Up
              </Link>
            )}
            {token && (
              <button className="dropdown-item" type="button" onClick={signout}>
                Sign Out
              </button>
            )}
          </div>
        </li>
        <li className="nav-item list-unstyled">
          <Link className="text-light" to="/cart">
            <i className="fa fa-shopping-cart" style={{ fontSize: 36 }} />
          </Link>
        </li>
      </div>
    </nav>
  );
};

export default Navbar;

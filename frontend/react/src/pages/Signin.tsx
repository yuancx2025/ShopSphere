import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import axios from 'axios';
import swal from 'sweetalert';
import logo from '../assets/icon.png';
import './Auth.css';

interface Props {
  baseURL: string;
}

const Signin: React.FC<Props> = ({ baseURL }) => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const signin = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setLoading(true);

    const user = { email, password };

    try {
      const res = await axios.post(`${baseURL}user/signIn`, user, {
        headers: { 'Content-Type': 'application/json' },
      });
      localStorage.setItem('token', res.data.token);
      navigate('/');
      swal({ text: 'Login successful. Please continue', icon: 'success' });
    } catch (err) {
      console.error(err);
      swal({ text: 'Unable to Log you in!', icon: 'error', closeOnClickOutside: false });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="container">
      <div className="row">
        <div className="col-12 text-center pt-3">
          <Link to="/">
            <img id="logo" src={logo} alt="logo" />
          </Link>
        </div>
      </div>
      <div className="row">
        <div className="col-12 justify-content-center d-flex flex-row pt-5">
          <div id="auth-div" className="flex-item border">
            <h2 className="pt-4 pl-4">Sign-In</h2>
            <form onSubmit={signin} className="pt-4 pl-4 pr-4">
              <div className="form-group">
                <label>Email</label>
                <input type="email" className="form-control" value={email} onChange={(e) => setEmail(e.target.value)} required />
              </div>
              <div className="form-group">
                <label>Password</label>
                <input type="password" className="form-control" value={password} onChange={(e) => setPassword(e.target.value)} required />
              </div>
              <small className="form-text text-muted">
                By continuing, you agree to Simplecoding's Conditions of Use and Privacy Notice.
              </small>
              <button type="submit" className="btn btn-primary mt-2 py-0">
                Continue
                {loading && (
                  <div className="spinner-border spinner-border-sm ml-2" role="status">
                    <span className="sr-only">Loading...</span>
                  </div>
                )}
              </button>
            </form>
            <hr />
            <small className="form-text text-muted pt-2 pl-4 text-center">New to Simplecoding?</small>
            <p className="text-center">
              <Link to="/signup" className="btn btn-dark text-center mx-auto px-5 py-1 mb-2">
                Create Your Simplecoding Account
              </Link>
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

export default Signin;

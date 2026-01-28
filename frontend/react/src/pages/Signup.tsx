import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import axios from 'axios';
import swal from 'sweetalert';
import logo from '../assets/icon.png';
import './Auth.css';

interface Props {
  baseURL: string;
}

const Signup: React.FC<Props> = ({ baseURL }) => {
  const [email, setEmail] = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const navigate = useNavigate();

  const signup = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (password !== passwordConfirm) {
      swal({ text: 'Error! Passwords are not matching', icon: 'error', closeOnClickOutside: false });
      return;
    }

    const user = { email, firstName, lastName, password };

    try {
      await axios.post(`${baseURL}user/signup`, user, {
        headers: { 'Content-Type': 'application/json' },
      });
      navigate('/');
      swal({ text: 'User signup successful. Please Login', icon: 'success', closeOnClickOutside: false });
    } catch (err) {
      console.error(err);
      swal({ text: 'Signup failed', icon: 'error', closeOnClickOutside: false });
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
            <h2 className="pt-4 pl-4">Create Account</h2>
            <form onSubmit={signup} className="pt-4 pl-4 pr-4">
              <div className="form-group">
                <label>Email</label>
                <input type="email" className="form-control" value={email} onChange={(e) => setEmail(e.target.value)} required />
              </div>
              <div className="form-row">
                <div className="col">
                  <div className="form-group">
                    <label>First Name</label>
                    <input type="text" className="form-control" value={firstName} onChange={(e) => setFirstName(e.target.value)} required />
                  </div>
                </div>
                <div className="col">
                  <div className="form-group">
                    <label>Last Name</label>
                    <input type="text" className="form-control" value={lastName} onChange={(e) => setLastName(e.target.value)} required />
                  </div>
                </div>
              </div>
              <div className="form-group">
                <label>Password</label>
                <input type="password" className="form-control" value={password} onChange={(e) => setPassword(e.target.value)} required />
              </div>
              <div className="form-group">
                <label>Confirm Password</label>
                <input
                  type="password"
                  className="form-control"
                  value={passwordConfirm}
                  onChange={(e) => setPasswordConfirm(e.target.value)}
                  required
                />
              </div>
              <button type="submit" className="btn btn-primary mt-2 py-0">
                Create Account
              </button>
            </form>
          </div>
        </div>
      </div>
    </div>
  );
};

export default Signup;

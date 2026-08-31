import React from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { isAdminUser } from '../../utils/auth';

interface Props {
  children: React.ReactElement;
}

export const RequireAuth: React.FC<Props> = ({ children }) => {
  const location = useLocation();
  if (!localStorage.getItem('token')) {
    return <Navigate to="/signin" replace state={{ from: location }} />;
  }
  return children;
};

export const RequireAdmin: React.FC<Props> = ({ children }) => {
  const location = useLocation();
  if (!localStorage.getItem('token')) {
    return <Navigate to="/signin" replace state={{ from: location }} />;
  }
  if (!isAdminUser()) {
    return <Navigate to="/" replace />;
  }
  return children;
};

import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import swal from 'sweetalert';
import authService, { SignInData, SignUpData } from '../api/services/authService';

interface User {
  id?: number;
  firstName?: string;
  lastName?: string;
  email?: string;
}

interface AuthContextType {
  token: string | null;
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  signIn: (data: SignInData) => Promise<void>;
  signUp: (data: SignUpData) => Promise<void>;
  signOut: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

interface AuthProviderProps {
  children: ReactNode;
}

export const AuthProvider: React.FC<AuthProviderProps> = ({ children }) => {
  const [token, setToken] = useState<string | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();

  // Initialize from localStorage
  useEffect(() => {
    const storedToken = localStorage.getItem('token');
    if (storedToken) {
      setToken(storedToken);
    }
  }, []);

  // Update token when location changes
  useEffect(() => {
    setToken(localStorage.getItem('token'));
  }, [location.pathname]);

  const signIn = async (data: SignInData) => {
    setIsLoading(true);
    try {
      const response = await authService.signIn(data);
      authService.setToken(response.token);
      setToken(response.token);
      if (response.user) {
        setUser(response.user);
      }
      navigate('/');
      swal({ text: 'Login successful. Please continue', icon: 'success' });
    } catch (error) {
      console.error(error);
      swal({ text: 'Unable to Log you in!', icon: 'error', closeOnClickOutside: false });
      throw error;
    } finally {
      setIsLoading(false);
    }
  };

  const signUp = async (data: SignUpData) => {
    setIsLoading(true);
    try {
      await authService.signUp(data);
      navigate('/signin');
      swal({ text: 'Account created successfully. Please sign in.', icon: 'success' });
    } catch (error) {
      console.error(error);
      swal({ text: 'Unable to create account!', icon: 'error', closeOnClickOutside: false });
      throw error;
    } finally {
      setIsLoading(false);
    }
  };

  const signOut = () => {
    authService.signOut();
    setToken(null);
    setUser(null);
    navigate('/');
    swal({
      text: 'Logged you out. Visit Again',
      icon: 'success',
      closeOnClickOutside: false,
    });
  };

  const value: AuthContextType = {
    token,
    user,
    isAuthenticated: !!token,
    isLoading,
    signIn,
    signUp,
    signOut,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export const useAuthContext = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuthContext must be used within an AuthProvider');
  }
  return context;
};

export default AuthContext;

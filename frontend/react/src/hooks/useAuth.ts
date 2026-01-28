import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import swal from 'sweetalert';
import authService, { SignInData, SignUpData } from '../api/services/authService';

export interface UseAuthReturn {
  token: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  signIn: (data: SignInData) => Promise<void>;
  signUp: (data: SignUpData) => Promise<void>;
  signOut: () => void;
}

export const useAuth = (): UseAuthReturn => {
  const [token, setToken] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const navigate = useNavigate();

  // Initialize token from localStorage
  useEffect(() => {
    const storedToken = localStorage.getItem('token');
    if (storedToken) {
      setToken(storedToken);
    }
  }, []);

  const signIn = useCallback(async (data: SignInData) => {
    setIsLoading(true);
    try {
      const response = await authService.signIn(data);
      authService.setToken(response.token);
      setToken(response.token);
      navigate('/');
      swal({ text: 'Login successful. Please continue', icon: 'success' });
    } catch (error) {
      console.error(error);
      swal({ text: 'Unable to Log you in!', icon: 'error', closeOnClickOutside: false });
      throw error;
    } finally {
      setIsLoading(false);
    }
  }, [navigate]);

  const signUp = useCallback(async (data: SignUpData) => {
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
  }, [navigate]);

  const signOut = useCallback(() => {
    authService.signOut();
    setToken(null);
    navigate('/');
    swal({
      text: 'Logged you out. Visit Again',
      icon: 'success',
      closeOnClickOutside: false,
    });
  }, [navigate]);

  return {
    token,
    isAuthenticated: !!token,
    isLoading,
    signIn,
    signUp,
    signOut,
  };
};

export default useAuth;

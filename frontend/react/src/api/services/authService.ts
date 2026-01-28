import { api } from '../client';
import { ENDPOINTS } from '../endpoints';

export interface SignUpData {
  firstName: string;
  lastName: string;
  email: string;
  password: string;
}

export interface SignInData {
  email: string;
  password: string;
}

export interface SignInResponse {
  token: string;
  user?: {
    id: number;
    firstName: string;
    lastName: string;
    email: string;
  };
}

export interface ApiResponse {
  success: boolean;
  message: string;
}

export const authService = {
  /**
   * Register a new user
   */
  signUp: async (data: SignUpData): Promise<ApiResponse> => {
    const response = await api.post<ApiResponse>(ENDPOINTS.AUTH.SIGN_UP, data);
    return response.data;
  },

  /**
   * Sign in an existing user
   */
  signIn: async (data: SignInData): Promise<SignInResponse> => {
    const response = await api.post<SignInResponse>(ENDPOINTS.AUTH.SIGN_IN, data);
    return response.data;
  },

  /**
   * Sign out the current user
   */
  signOut: (): void => {
    localStorage.removeItem('token');
  },

  /**
   * Check if user is authenticated
   */
  isAuthenticated: (): boolean => {
    return !!localStorage.getItem('token');
  },

  /**
   * Get the current auth token
   */
  getToken: (): string | null => {
    return localStorage.getItem('token');
  },

  /**
   * Set the auth token
   */
  setToken: (token: string): void => {
    localStorage.setItem('token', token);
  },
};

export default authService;

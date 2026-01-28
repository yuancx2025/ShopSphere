import axios, { AxiosInstance, AxiosRequestConfig, AxiosResponse, AxiosError } from 'axios';
import { API_BASE_URL } from '../utils/constants';

// Create axios instance with default config
const apiClient: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 10000,
});

// Request interceptor
apiClient.interceptors.request.use(
  (config) => {
    // Add token to request if available
    const token = localStorage.getItem('token');
    if (token && config.params) {
      config.params.token = token;
    }
    return config;
  },
  (error: AxiosError) => {
    return Promise.reject(error);
  }
);

// Response interceptor
apiClient.interceptors.response.use(
  (response: AxiosResponse) => {
    return response;
  },
  (error: AxiosError) => {
    // Handle common errors
    if (error.response) {
      switch (error.response.status) {
        case 401:
          // Unauthorized - clear token and redirect to login
          localStorage.removeItem('token');
          window.location.href = '/signin';
          break;
        case 403:
          console.error('Forbidden access');
          break;
        case 404:
          console.error('Resource not found');
          break;
        case 500:
          console.error('Server error');
          break;
        default:
          console.error('An error occurred');
      }
    } else if (error.request) {
      console.error('Network error - no response received');
    } else {
      console.error('Error setting up request:', error.message);
    }
    return Promise.reject(error);
  }
);

// Helper functions for common HTTP methods
export const api = {
  get: <T>(url: string, config?: AxiosRequestConfig): Promise<AxiosResponse<T>> =>
    apiClient.get<T>(url, config),

  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<AxiosResponse<T>> =>
    apiClient.post<T>(url, data, config),

  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<AxiosResponse<T>> =>
    apiClient.put<T>(url, data, config),

  patch: <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<AxiosResponse<T>> =>
    apiClient.patch<T>(url, data, config),

  delete: <T>(url: string, config?: AxiosRequestConfig): Promise<AxiosResponse<T>> =>
    apiClient.delete<T>(url, config),
};

export default apiClient;

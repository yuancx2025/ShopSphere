import { VALIDATION } from './constants';

/**
 * Validate email format
 */
export const isValidEmail = (email: string): boolean => {
  return VALIDATION.EMAIL_PATTERN.test(email);
};

/**
 * Validate password strength
 */
export const isValidPassword = (password: string): boolean => {
  return password.length >= VALIDATION.PASSWORD_MIN_LENGTH;
};

/**
 * Validate name (first name, last name)
 */
export const isValidName = (name: string): boolean => {
  const trimmed = name.trim();
  return (
    trimmed.length >= VALIDATION.NAME_MIN_LENGTH &&
    trimmed.length <= VALIDATION.NAME_MAX_LENGTH
  );
};

/**
 * Validate required field
 */
export const isRequired = (value: string | number | null | undefined): boolean => {
  if (value === null || value === undefined) return false;
  if (typeof value === 'string') return value.trim().length > 0;
  return true;
};

/**
 * Validate positive number
 */
export const isPositiveNumber = (value: number): boolean => {
  return !isNaN(value) && value > 0;
};

/**
 * Validate URL format
 */
export const isValidUrl = (url: string): boolean => {
  try {
    new URL(url);
    return true;
  } catch {
    return false;
  }
};

/**
 * Validate image URL (checks for common image extensions)
 */
export const isValidImageUrl = (url: string): boolean => {
  if (!isValidUrl(url)) return false;
  const imageExtensions = ['.jpg', '.jpeg', '.png', '.gif', '.webp', '.svg'];
  const lowercaseUrl = url.toLowerCase();
  return imageExtensions.some((ext) => lowercaseUrl.includes(ext));
};

/**
 * Validation result interface
 */
export interface ValidationResult {
  isValid: boolean;
  errors: Record<string, string>;
}

/**
 * Validate sign-up form
 */
export const validateSignUpForm = (data: {
  firstName: string;
  lastName: string;
  email: string;
  password: string;
  confirmPassword?: string;
}): ValidationResult => {
  const errors: Record<string, string> = {};

  if (!isRequired(data.firstName)) {
    errors.firstName = 'First name is required';
  } else if (!isValidName(data.firstName)) {
    errors.firstName = `First name must be between ${VALIDATION.NAME_MIN_LENGTH} and ${VALIDATION.NAME_MAX_LENGTH} characters`;
  }

  if (!isRequired(data.lastName)) {
    errors.lastName = 'Last name is required';
  } else if (!isValidName(data.lastName)) {
    errors.lastName = `Last name must be between ${VALIDATION.NAME_MIN_LENGTH} and ${VALIDATION.NAME_MAX_LENGTH} characters`;
  }

  if (!isRequired(data.email)) {
    errors.email = 'Email is required';
  } else if (!isValidEmail(data.email)) {
    errors.email = 'Please enter a valid email address';
  }

  if (!isRequired(data.password)) {
    errors.password = 'Password is required';
  } else if (!isValidPassword(data.password)) {
    errors.password = `Password must be at least ${VALIDATION.PASSWORD_MIN_LENGTH} characters`;
  }

  if (data.confirmPassword !== undefined && data.password !== data.confirmPassword) {
    errors.confirmPassword = 'Passwords do not match';
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  };
};

/**
 * Validate sign-in form
 */
export const validateSignInForm = (data: {
  email: string;
  password: string;
}): ValidationResult => {
  const errors: Record<string, string> = {};

  if (!isRequired(data.email)) {
    errors.email = 'Email is required';
  } else if (!isValidEmail(data.email)) {
    errors.email = 'Please enter a valid email address';
  }

  if (!isRequired(data.password)) {
    errors.password = 'Password is required';
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  };
};

/**
 * Validate product form
 */
export const validateProductForm = (data: {
  name: string;
  description: string;
  price: number;
  imageURL: string;
  categoryId: number;
}): ValidationResult => {
  const errors: Record<string, string> = {};

  if (!isRequired(data.name)) {
    errors.name = 'Product name is required';
  }

  if (!isRequired(data.description)) {
    errors.description = 'Description is required';
  } else if (data.description.length > VALIDATION.DESCRIPTION_MAX_LENGTH) {
    errors.description = `Description must be less than ${VALIDATION.DESCRIPTION_MAX_LENGTH} characters`;
  }

  if (!isPositiveNumber(data.price)) {
    errors.price = 'Price must be a positive number';
  }

  if (!isRequired(data.imageURL)) {
    errors.imageURL = 'Image URL is required';
  } else if (!isValidUrl(data.imageURL)) {
    errors.imageURL = 'Please enter a valid URL';
  }

  if (!isPositiveNumber(data.categoryId)) {
    errors.categoryId = 'Please select a category';
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  };
};

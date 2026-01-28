import React, { InputHTMLAttributes, forwardRef, useState } from 'react';
import './Input.css';

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
  helperText?: string;
  leftIcon?: React.ReactNode;
  rightIcon?: React.ReactNode;
  fullWidth?: boolean;
}

const Input = forwardRef<HTMLInputElement, InputProps>(
  (
    {
      label,
      error,
      helperText,
      leftIcon,
      rightIcon,
      fullWidth = false,
      className = '',
      type = 'text',
      id,
      ...props
    },
    ref
  ) => {
    const [showPassword, setShowPassword] = useState(false);
    const inputId = id || `input-${Math.random().toString(36).substr(2, 9)}`;

    const isPasswordType = type === 'password';
    const actualType = isPasswordType && showPassword ? 'text' : type;

    const containerClass = `input-container ${fullWidth ? 'input-full-width' : ''} ${className}`;
    const inputWrapperClass = `input-wrapper ${error ? 'input-error' : ''} ${leftIcon ? 'has-left-icon' : ''} ${rightIcon || isPasswordType ? 'has-right-icon' : ''}`;

    return (
      <div className={containerClass}>
        {label && (
          <label htmlFor={inputId} className="input-label">
            {label}
          </label>
        )}
        <div className={inputWrapperClass}>
          {leftIcon && <span className="input-icon input-icon-left">{leftIcon}</span>}
          <input
            ref={ref}
            id={inputId}
            type={actualType}
            className="input-field"
            aria-invalid={!!error}
            aria-describedby={error ? `${inputId}-error` : undefined}
            {...props}
          />
          {isPasswordType && (
            <button
              type="button"
              className="input-icon input-icon-right password-toggle"
              onClick={() => setShowPassword(!showPassword)}
              tabIndex={-1}
            >
              {showPassword ? (
                <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
                  <line x1="1" y1="1" x2="23" y2="23"/>
                </svg>
              ) : (
                <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                  <circle cx="12" cy="12" r="3"/>
                </svg>
              )}
            </button>
          )}
          {rightIcon && !isPasswordType && (
            <span className="input-icon input-icon-right">{rightIcon}</span>
          )}
        </div>
        {error && (
          <span id={`${inputId}-error`} className="input-error-text" role="alert">
            {error}
          </span>
        )}
        {helperText && !error && (
          <span className="input-helper-text">{helperText}</span>
        )}
      </div>
    );
  }
);

Input.displayName = 'Input';

export default Input;

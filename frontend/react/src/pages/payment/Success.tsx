import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';

interface Props {
  baseURL: string;
}

const PaymentSuccess: React.FC<Props> = ({ baseURL }) => {
  const [token, setToken] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    setToken(localStorage.getItem('token'));
    setSessionId(localStorage.getItem('sessionId'));
  }, []);

  useEffect(() => {
    if (!token || !sessionId) return;
    axios
      .post(`${baseURL}order/add/?token=${token}&sessionId=${sessionId}`)
      .then(() => navigate('/orders'))
      .catch((error) => console.error(error));
  }, [baseURL, navigate, sessionId, token]);

  return (
    <div className="alert alert-success" role="alert" id="message">
      Payment successful
    </div>
  );
};

export default PaymentSuccess;

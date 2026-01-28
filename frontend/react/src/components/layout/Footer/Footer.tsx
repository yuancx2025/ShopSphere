import React from 'react';
import logo from '../../assets/icon.png';
import './Footer.css';

const Footer: React.FC = () => (
  <footer>
    <div className="container pt-5">
      <div className="row">
        <div className="col-md-3 col-6">
          <ul style={{ listStyleType: 'none' }}>
            <li className="text-light font-weight-bold pb-2">Get to Know Us</li>
            <li><a className="footer-link font-weight-light" href="#">About Us</a></li>
            <li><a className="footer-link font-weight-light" href="#">Android App</a></li>
            <li><a className="footer-link font-weight-light" href="#">iOS App</a></li>
          </ul>
        </div>

        <div className="col-md-3 col-6">
          <ul style={{ listStyleType: 'none' }}>
            <li className="text-light font-weight-bold">Connect With Us</li>
            <li><a className="footer-link font-weight-light" href="#">Facebook</a></li>
            <li><a className="footer-link font-weight-light" href="#">Twitter</a></li>
            <li><a className="footer-link font-weight-light" href="#">Instagram</a></li>
          </ul>
        </div>

        <div className="col-md-3 col-6">
          <ul style={{ listStyleType: 'none' }}>
            <li className="text-light font-weight-bold">Make Money With Us</li>
            <li><a className="footer-link font-weight-light" href="#">Sell with Us</a></li>
            <li><a className="footer-link font-weight-light" href="#">Become an Affiliate</a></li>
            <li><a className="footer-link font-weight-light" href="#">Advertise Your Products</a></li>
          </ul>
        </div>

        <div className="col-md-3 col-6">
          <ul style={{ listStyleType: 'none' }}>
            <li className="text-light font-weight-bold">Let Us Help You</li>
            <li><a className="footer-link font-weight-light" href="#">Return Centre</a></li>
            <li><a className="footer-link font-weight-light" href="#">100% Purchase Protection</a></li>
            <li><a className="footer-link font-weight-light" href="#">Help</a></li>
            <li><a className="footer-link font-weight-light" href="#">App Download</a></li>
          </ul>
        </div>
      </div>

      <div className="row">
        <div className="col-12">
          <div className="text-center py-5">
            <img id="footer-logo" src={logo} alt="logo" />
          </div>
        </div>
      </div>
    </div>
  </footer>
);

export default Footer;

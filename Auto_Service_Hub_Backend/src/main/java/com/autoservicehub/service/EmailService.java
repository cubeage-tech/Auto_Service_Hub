package com.autoservicehub.service;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    public void sendOtpEmail(String toEmail, String otp) {

        SimpleMailMessage message = new SimpleMailMessage();

        message.setTo(toEmail);
        message.setSubject("SmartGarage AI - Password Reset OTP");

        message.setText(
                "Hello,\n\n" +
                "Your SmartGarage AI password reset OTP is:\n\n" +
                otp + "\n\n" +
                "This OTP is valid for 10 minutes and can be used only once.\n\n" +
                "If you did not request a password reset, please ignore this email.\n\n" +
                "Regards,\n" +
                "SmartGarage AI CRM"
        );

        mailSender.send(message);
    }
}
package com.autoservicehub.service;

import com.autoservicehub.dto.ResetPasswordRequest;
import com.autoservicehub.dto.VerifyOtpRequest;
import com.autoservicehub.entity.PasswordResetOtp;
import com.autoservicehub.entity.User;
import com.autoservicehub.repository.PasswordResetOtpRepository;
import com.autoservicehub.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final int OTP_LENGTH = 6;
    private static final int OTP_VALIDITY_MINUTES = 10;

    private final UserRepository userRepository;
    private final PasswordResetOtpRepository otpRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    private final SecureRandom secureRandom = new SecureRandom();

    public void sendOtp(String email) {

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() ->
                        new IllegalArgumentException("No account found with this email address"));

        String otp = generateOtp();

        // Invalidate the previous unused OTP
        otpRepository.findTopByUserIdAndUsedFalseOrderByIdDesc(user.getId())
                .ifPresent(previousOtp -> {
                    previousOtp.setUsed(true);
                    otpRepository.save(previousOtp);
                });

        PasswordResetOtp passwordResetOtp = new PasswordResetOtp();
        passwordResetOtp.setUserId(user.getId());
        passwordResetOtp.setOtpHash(passwordEncoder.encode(otp));
        passwordResetOtp.setExpiresAt(
                LocalDateTime.now().plusMinutes(OTP_VALIDITY_MINUTES)
        );
        passwordResetOtp.setUsed(false);

        otpRepository.save(passwordResetOtp);

        emailService.sendOtpEmail(user.getEmail(), otp);
    }

    public void verifyOtp(VerifyOtpRequest request) {

        User user = findUserByEmail(request.getEmail());

        PasswordResetOtp otpRecord = getValidOtp(user.getId());

        if (!passwordEncoder.matches(request.getOtp(), otpRecord.getOtpHash())) {
            throw new IllegalArgumentException("Invalid OTP");
        }
    }

    public void resetPassword(ResetPasswordRequest request) {

        User user = findUserByEmail(request.getEmail());

        PasswordResetOtp otpRecord = getValidOtp(user.getId());

        if (!passwordEncoder.matches(request.getOtp(), otpRecord.getOtpHash())) {
            throw new IllegalArgumentException("Invalid OTP");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // OTP can only be used once
        otpRecord.setUsed(true);
        otpRepository.save(otpRecord);
    }

    private User findUserByEmail(String email) {

        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() ->
                        new IllegalArgumentException("No account found with this email address"));
    }

    private PasswordResetOtp getValidOtp(Long userId) {

        PasswordResetOtp otpRecord = otpRepository
                .findTopByUserIdAndUsedFalseOrderByIdDesc(userId)
                .orElseThrow(() ->
                        new IllegalArgumentException("OTP not found or already used"));

        if (otpRecord.getExpiresAt().isBefore(LocalDateTime.now())) {
            otpRecord.setUsed(true);
            otpRepository.save(otpRecord);

            throw new IllegalArgumentException("OTP has expired");
        }

        return otpRecord;
    }

    private String generateOtp() {

        int otp = secureRandom.nextInt(900000) + 100000;

        return String.valueOf(otp);
    }
}
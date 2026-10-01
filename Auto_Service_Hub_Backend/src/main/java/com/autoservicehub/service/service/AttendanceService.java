package com.autoservicehub.service;

import com.autoservicehub.dto.AttendanceResponseDTO;
import java.util.List;

public interface AttendanceService {
    AttendanceResponseDTO checkIn(Long mechanicId);
    AttendanceResponseDTO checkOut(Long mechanicId);
    List<AttendanceResponseDTO> history(Long mechanicId);
}
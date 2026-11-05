package com.fancy.rideagenthub.domain.dto;

import com.fancy.rideagenthub.agentbase.amap.pojo.georegeo.Regeocode;
import com.fancy.rideagenthub.agentbase.amap.service.AmapGeoRegeoService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class CreateOrderDTO {

    private String userId;

    private String mongoTraceId;

    private Integer vehicleType;

    private Integer isReservation;

    private Integer isExpedited;

    private LocalDateTime scheduledTime;

    private String startAddress;

    private BigDecimal startLat;

    private BigDecimal startLng;

    private String endAddress;

    private BigDecimal endLat;

    private BigDecimal endLng;

    private BigDecimal estPrice;

    private BigDecimal estDistance;

    private BigDecimal radio;

    /**
     * 校验结果封装
     */
    public record ValidationResult(
            boolean isComplete,
            List<String> missingFields,
            List<String> warnings,
            CreateOrderDTO supplementedDTO
    ) {}

    /**
     * 校验并补充订单信息
     *
     * @param regeoService 逆地理编码服务（用于补充地址）
     * @return 校验结果
     */
    public ValidationResult validateAndSupplement(AmapGeoRegeoService regeoService) {
        List<String> missingFields = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 复制当前对象用于修改
        CreateOrderDTO supplemented = CreateOrderDTO.builder()
                .userId(this.userId)
                .mongoTraceId(this.mongoTraceId)
                .vehicleType(this.vehicleType)
                .isReservation(this.isReservation)
                .isExpedited(this.isExpedited)
                .scheduledTime(this.scheduledTime)
                .startAddress(this.startAddress)
                .startLat(this.startLat)
                .startLng(this.startLng)
                .endAddress(this.endAddress)
                .endLat(this.endLat)
                .endLng(this.endLng)
                .estPrice(this.estPrice)
                .estDistance(this.estDistance)
                .radio(this.radio)
                .build();

        // 1. userId 必填校验
        if (normalizeText(supplemented.getUserId()) == null) {
            missingFields.add("userId");
        }

        // 2. 经纬度必填校验（核心字段，不可省略）
        if (supplemented.getStartLat() == null || supplemented.getStartLng() == null) {
            missingFields.add("startLat/startLng");
        }
        if (supplemented.getEndLat() == null || supplemented.getEndLng() == null) {
            missingFields.add("endLat/endLng");
        }

        // 3. 预约单时间校验
        int isReservation = supplemented.getIsReservation() == null ? 0 : supplemented.getIsReservation();
        if (isReservation == 1 && supplemented.getScheduledTime() == null) {
            missingFields.add("scheduledTime");
        }

        // 4. 地址补充：如果地址为空但经纬度存在，调用逆地理编码
        // 起点地址补充
        if (normalizeText(supplemented.getStartAddress()) == null
                && supplemented.getStartLat() != null && supplemented.getStartLng() != null) {
            String supplementedStartAddress = supplementAddress(regeoService,
                    supplemented.getStartLng().doubleValue(),
                    supplemented.getStartLat().doubleValue());
            if (supplementedStartAddress != null) {
                supplemented.setStartAddress(supplementedStartAddress);
            } else {
                warnings.add("起点地址无法通过逆地理编码获取，将使用空地址");
            }
        }

        // 终点地址补充
        if (normalizeText(supplemented.getEndAddress()) == null
                && supplemented.getEndLat() != null && supplemented.getEndLng() != null) {
            String supplementedEndAddress = supplementAddress(regeoService,
                    supplemented.getEndLng().doubleValue(),
                    supplemented.getEndLat().doubleValue());
            if (supplementedEndAddress != null) {
                supplemented.setEndAddress(supplementedEndAddress);
            } else {
                warnings.add("终点地址无法通过逆地理编码获取，将使用空地址");
            }
        }

        return new ValidationResult(
                missingFields.isEmpty(),
                missingFields,
                warnings,
                supplemented
        );
    }

    /**
     * 通过逆地理编码补充地址
     *
     * @param regeoService 逆地理编码服务
     * @param longitude 经度
     * @param latitude 纬度
     * @return 补充的地址，失败返回 null
     */
    private String supplementAddress(AmapGeoRegeoService regeoService, double longitude, double latitude) {
        if (regeoService == null) {
            log.warn("逆地理编码服务未注入，无法补充地址");
            return null;
        }

        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                Optional<Regeocode> regeocode = regeoService.getRegeo(longitude, latitude);
                if (regeocode.isPresent()) {
                    String formattedAddress = regeocode.get().formattedAddress();
                    if (formattedAddress != null && !formattedAddress.isBlank()) {
                        return formattedAddress;
                    }
                }
                // 如果结果为空，稍等后重试
                if (i < maxRetries - 1) {
                    Thread.sleep(100L * (i + 1));
                }
            } catch (Exception e) {
                log.warn("逆地理编码失败，第{}次重试: {}", i + 1, e.getMessage());
                if (i < maxRetries - 1) {
                    try {
                        Thread.sleep(100L * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 文本规范化：trim 后空字符串返回 null
     */
    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

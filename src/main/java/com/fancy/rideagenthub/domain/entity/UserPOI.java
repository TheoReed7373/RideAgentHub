package com.fancy.rideagenthub.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fancy.rideagenthub.domain.dto.UserPoiDTO;
import com.fancy.rideagenthub.domain.vo.POIInfoVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user_poi")
public class UserPOI implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 兴趣点标签，如“家”、“公司”等
     */
    @TableField("poi_tag")
    private String poiTag;

    /**
     * 兴趣点名，如“XX公司”
     */
    @TableField("poi_name")
    private String poiName;

    /**
     * 兴趣点位置/详细地址
     */
    @TableField("poi_address")
    private String poiAddress;

    /**
     * 经度
     */
    @TableField("longitude")
    private BigDecimal longitude;

    /**
     * 纬度
     */
    @TableField("latitude")
    private BigDecimal latitude;

    public UserPOI(POIInfoVO poiInfoVO){
        this.poiTag = poiInfoVO.getPoiTag();
        this.poiName = poiInfoVO.getPoiName();
        this.poiAddress = poiInfoVO.getPoiAddress();
        this.longitude = poiInfoVO.getLongitude();
        this.latitude = poiInfoVO.getLatitude();
    }

    public UserPOI(UserPoiDTO userPoiDTO){
        this.id = Long.valueOf(userPoiDTO.getId());
        this.poiTag = userPoiDTO.getPoiTag();
        this.poiName = userPoiDTO.getPoiName();
        this.poiAddress = userPoiDTO.getPoiAddress();
        this.longitude = userPoiDTO.getLongitude();
        this.latitude = userPoiDTO.getLatitude();
    }
}

package com.fancy.rideagenthub.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("bus_citycode")
public class CityCode {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 城市代码
     **/
    @TableField("citycode")
    private String cityCode;

    /**
     * 城市名称
     */
    @TableField("name")
    private String name;

    /**
     * 城市简称/别名
     */
    @TableField("simple_name")
    private String simpleName;
}

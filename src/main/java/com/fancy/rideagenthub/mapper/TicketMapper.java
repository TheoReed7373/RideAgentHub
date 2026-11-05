package com.fancy.rideagenthub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.rideagenthub.domain.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {
}

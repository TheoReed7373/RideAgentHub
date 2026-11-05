package com.fancy.rideagenthub.util;

import java.time.LocalDateTime;
import java.time.ZoneId;

public class TimeUtil {
    //XX年XX月XX日
    public static String getCurrentDate(){
        LocalDateTime nowInShanghai = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        return String.format("%d年%02d月%02d日", 
            nowInShanghai.getYear(), 
            nowInShanghai.getMonthValue(), 
            nowInShanghai.getDayOfMonth());
    }

    //XXXX年XX月XX日 XX时XX分XX秒
    public static String getDetailCurrentTime(){
        LocalDateTime nowInShanghai = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        return String.format("%d年%02d月%02d日 %02d时%02d分%02d秒", 
            nowInShanghai.getYear(), 
            nowInShanghai.getMonthValue(), 
            nowInShanghai.getDayOfMonth(),
            nowInShanghai.getHour(),
            nowInShanghai.getMinute(),
            nowInShanghai.getSecond());
    }

    public static String getMinutes(String seconds){
        try{
            int sec = Integer.parseInt(seconds);
            return String.format("%02d", sec / 60);
        }catch(Exception e){
            return "0";
        }
    }

    /**
     * 兼容解析：支持 "yyyy-MM-dd HH:mm:ss" 和 "yyyy-MM-ddTHH:mm:ss"
     */
    public static LocalDateTime parse(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return null; // 或者根据业务抛出异常
        }
        // 核心技法：把空格替换成 'T'，瞬间符合 ISO 标准
        // 如果原本就是 'T'，这行代码不会有副作用
        String isoTimeStr = timeStr.replace(" ", "T");

        return LocalDateTime.parse(isoTimeStr);
    }
}

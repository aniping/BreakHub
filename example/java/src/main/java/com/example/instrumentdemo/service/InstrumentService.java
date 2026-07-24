package com.example.instrumentdemo.service;

import com.example.instrumentdemo.annotation.Description;
import com.example.instrumentdemo.annotation.EntryDefine;
import com.example.instrumentdemo.annotation.ParameterDefine;
import com.example.instrumentdemo.model.ValueResult;
import java.util.Map;

public interface InstrumentService {
    @EntryDefine("仪表初始化")
    @Description("初始化指定类型和编号的仪表")
    ValueResult instrumentInitialize(
            @ParameterDefine("仪表类型") @Description("仪表类型") String instType,
            @ParameterDefine("仪表编号") @Description("仪表编号") int slotId,
            @ParameterDefine("参数") @Description("扩展参数") Map<String, Object> params);

    @EntryDefine("仪表控制")
    @Description("通过槽位号转换 hid 号，操作对应仪器仪表实例")
    ValueResult instrumentControl(
            @ParameterDefine("仪表类型") @Description("仪表类型") String instType,
            @ParameterDefine("仪表操作") @Description("仪表操作") String cmdName,
            @ParameterDefine("槽位id") @Description("槽位id") int slotId,
            @ParameterDefine("参数") @Description("扩展参数") Map<String, Object> params);
}

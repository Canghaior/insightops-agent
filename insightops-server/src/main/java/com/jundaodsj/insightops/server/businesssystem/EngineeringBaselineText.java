package com.jundaodsj.insightops.server.businesssystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.businesssystem.application.EngineeringBaselineStore.*;
import java.util.*;
import java.util.stream.Collectors;

/** A readable projection of the frozen assessment input, shared by the research prompt and the saved report. */
public final class EngineeringBaselineText {
    private EngineeringBaselineText() {}
    public static String render(Map<String,Object> inputs,ObjectMapper json) {
        if(!(inputs.get("engineeringBaseline") instanceof Map<?,?> snapshot)) return "本次评估未关联工程基线。\n";
        var meta=(Map<?,?>)snapshot.get("baseline");
        var c=json.convertValue(snapshot.get("content"),Content.class);
        var out=new StringBuilder("版本 ").append(meta.get("version")).append(" · ").append(c.label())
                .append("（").append(meta.get("id")).append("）\n\n资料声明的来源标识：").append(c.evidence().sourceLabel())
                .append("\n\n原件 SHA-256：").append(c.originalSha256()).append("。哈希仅核对字节一致性。\n\n");
        for(var f:c.evidence().facts()) {
            out.append("- ").append(switch(f.kind()){
                case DECLARATION -> "POM 声明"; case STATIC_DERIVATION -> "静态推导"; case MAVEN_RESOLUTION -> "Maven 解析记录";
                case BUILD_ENVIRONMENT -> "构建工具环境"; case RUNTIME_OBSERVATION -> "应用进程观察";
            }).append(" · ").append(f.subject()).append(" = ").append(f.value()).append("（").append(location(f.source()));
            if(!f.via().isEmpty()) out.append("，属性来源 ").append(f.via().stream().map(EngineeringBaselineText::location).collect(Collectors.joining("、")));
            out.append("）\n");
        }
        out.append("\n未知与适用范围：\n");
        c.evidence().gaps().forEach(g -> out.append("- ").append(g).append('\n'));
        if(!c.note().isEmpty()) out.append("\n导入者说明（自述）：").append(c.note()).append('\n');
        if(!c.expectedJava().isEmpty()) out.append("\n导入者填写的 POM Java 版本：").append(c.expectedJava()).append('\n');
        if(!c.profileAtImport().technologyStack().isBlank()) out.append("\n导入时档案自述（修订 ").append(c.profileAtImport().revision()).append("）：").append(c.profileAtImport().technologyStack()).append('\n');
        c.conflicts().forEach(g -> out.append("\n待核对差异：").append(g).append('\n'));
        return out.toString();
    }
    private static String location(Source s) {return s.path()+":"+s.line();}
    public static Map<String,Object> promptInputs(Map<String,Object> inputs,ObjectMapper json) {
        if(!inputs.containsKey("engineeringBaseline")) return inputs;
        var result=new LinkedHashMap<>(inputs);
        result.put("engineeringBaseline",render(inputs,json));
        return result;
    }
}


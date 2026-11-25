package com.jundaodsj.insightops.server.localexecution;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

record LocalBuildPlan(Mode mode, List<String> testClasses) {
    enum Mode { ALL_TESTS, SELECTED_CLASSES }
    static final LocalBuildPlan ALL = new LocalBuildPlan(Mode.ALL_TESTS, List.of());
    private static final Pattern CLASS = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    LocalBuildPlan {
        if (mode == null) throw new IllegalArgumentException("请选择 Maven 构建方案。");
        testClasses = testClasses == null ? List.of() : testClasses;
        if (mode == Mode.ALL_TESTS && !testClasses.isEmpty()) throw new IllegalArgumentException("默认测试方案不接受类选择器。");
        if (mode == Mode.SELECTED_CLASSES && (testClasses.isEmpty() || testClasses.size() > 12)) {
            throw new IllegalArgumentException("请选择 1 至 12 个测试类。");
        }
        for (String name : testClasses) {
            if (name == null || name.length() > 160 || !CLASS.matcher(name).matches()) {
                throw new IllegalArgumentException("只接受测试类名或完整 Java 类名。");
            }
        }
        testClasses = testClasses.stream().distinct().toList();
    }

    List<String> command() {
        var args = new ArrayList<>(List.of("mvn", "-B", "-ntp", "-o", "-Dstyle.color=never", "-Dmaven.repo.local=/offline-repo"));
        if (mode == Mode.SELECTED_CLASSES) args.add("-Dtest=" + String.join(",", testClasses));
        args.add("test");
        return List.copyOf(args);
    }
}

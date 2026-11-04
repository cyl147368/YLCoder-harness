package dev.codeharness.cli.tools;

import dev.codeharness.cli.approval.MavenBuildExecution;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 命令审批必须绑定工作区、原始命令以及最终执行计划。 */
@FunctionalInterface
public interface CommandApproval {
    Decision approve(Request request);

    record Request(Path workspace, String command) {
        public Request {
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(command, "command");
        }
    }

    /** 原生查询、绑定构建和固定程序参数互斥；没有计划才表示显式批准原始 shell 命令。 */
    record Decision(boolean approved, List<String> arguments, NativeReadOnlyQuery.Plan nativeQuery, MavenBuildExecution.Plan mavenBuild) {
        public Decision {
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
            if (!approved && (!arguments.isEmpty() || nativeQuery != null || mavenBuild != null))
                throw new IllegalArgumentException("拒绝的命令不能携带执行计划");
            if ((arguments.isEmpty() ? 0 : 1) + (nativeQuery == null ? 0 : 1) + (mavenBuild == null ? 0 : 1) > 1)
                throw new IllegalArgumentException("命令只能携带一种执行计划");
            if (arguments.stream().anyMatch(value -> value.isEmpty() || value.indexOf('\0') >= 0))
                throw new IllegalArgumentException("执行参数不能为空或包含空字符");
        }

        public Decision(boolean approved, List<String> arguments) { this(approved, arguments, null, null); }
        public Decision(boolean approved, List<String> arguments, NativeReadOnlyQuery.Plan nativeQuery) { this(approved, arguments, nativeQuery, null); }

        public static Decision denied() { return new Decision(false, List.of()); }
        public static Decision shell() { return new Decision(true, List.of()); }
        public static Decision direct(List<String> arguments) {
            if (arguments.isEmpty()) throw new IllegalArgumentException("直接执行必须提供程序路径");
            return new Decision(true, arguments);
        }
        public static Decision nativeQuery(NativeReadOnlyQuery.Plan plan) {
            return new Decision(true, List.of(), Objects.requireNonNull(plan, "plan"));
        }
        public static Decision mavenBuild(MavenBuildExecution.Plan plan) {
            return new Decision(true, List.of(), null, Objects.requireNonNull(plan, "plan"));
        }
    }
}

package dev.codeharness.cli.tools;

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

    /** 空参数列表仅表示用户显式批准原始 shell 命令。 */
    record Decision(boolean approved, List<String> arguments) {
        public Decision {
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
            if (!approved && !arguments.isEmpty()) throw new IllegalArgumentException("拒绝的命令不能携带执行参数");
            if (arguments.stream().anyMatch(value -> value.isEmpty() || value.indexOf('\0') >= 0))
                throw new IllegalArgumentException("执行参数不能为空或包含空字符");
        }

        public static Decision denied() { return new Decision(false, List.of()); }
        public static Decision shell() { return new Decision(true, List.of()); }
        public static Decision direct(List<String> arguments) {
            if (arguments.isEmpty()) throw new IllegalArgumentException("直接执行必须提供程序路径");
            return new Decision(true, arguments);
        }
    }
}

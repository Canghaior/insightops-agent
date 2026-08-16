package com.jundaodsj.insightops.foundation.error;

public final class InvalidStateTransitionException extends IllegalStateException {

    public InvalidStateTransitionException(Enum<?> current, Enum<?> target) {
        super("不允许从状态 " + current + " 转换到 " + target);
    }
}

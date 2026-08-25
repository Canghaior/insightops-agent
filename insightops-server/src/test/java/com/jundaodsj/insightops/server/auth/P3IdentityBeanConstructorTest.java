package com.jundaodsj.insightops.server.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class P3IdentityBeanConstructorTest {
    @Test
    void everyIdentityBeanWithTestOnlyConstructorsDeclaresItsInjectionConstructor() {
        assertSingleInjectionConstructor(TotpService.class);
        assertSingleInjectionConstructor(IdentityLifecycleService.class);
        assertSingleInjectionConstructor(WorkspaceManagementService.class);
        assertSingleInjectionConstructor(IdentityActionRateLimiter.class);
        assertSingleInjectionConstructor(AccountDeletionScheduler.class);
        assertSingleInjectionConstructor(IdentityMailOutboxSender.class);
    }

    private static void assertSingleInjectionConstructor(Class<?> type) {
        Constructor<?>[] injectionConstructors = Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .toArray(Constructor<?>[]::new);

        assertThat(injectionConstructors)
                .as("explicit Spring injection constructor for %s", type.getSimpleName())
                .hasSize(1);
        assertThat(Modifier.isPublic(injectionConstructors[0].getModifiers()))
                .as("public Spring injection constructor for %s", type.getSimpleName())
                .isTrue();
    }
}

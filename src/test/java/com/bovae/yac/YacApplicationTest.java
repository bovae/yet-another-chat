package com.bovae.yac;

import com.bovae.yac.config.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class YacApplicationTest {

    @Test
    void contextLoads() {}
}

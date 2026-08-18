package cn.zimu.fulfillment.common.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Legacy integration fixtures call controllers directly; this bean never ships in the production jar. */
@Configuration(proxyBeanMethods = false)
@Profile("test-fixtures")
public class TestRequestAuthenticationConfiguration {

    @Bean
    RequestAuthenticationPolicy permissiveTestRequestAuthenticationPolicy() {
        return request -> RequestAuthenticationPolicy.Requirement.NONE;
    }
}

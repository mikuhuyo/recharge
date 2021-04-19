package com.recharge.supplier.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

/**
 * @author yuelimin
 * @version 1.0.0
 * @since 1.8
 */
@Configuration
@EnableSwagger2
@ConditionalOnProperty(prefix = "swagger", value = {"enable"}, havingValue = "true")
public class SwaggerConfiguration {

    @Bean
    public Docket api() {
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo())
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.recharge"))
                .paths(PathSelectors.any())
                .build();
    }

    /**
     * 构建api文档基本信息
     *
     * @return
     */
    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("充吧接口文档-供应商服务")
                .description("供应商服务接口文档")
                // 服务条款网址
                .termsOfServiceUrl("https://github.com/YueLiMin-say")
                .version("1.0.0")
                .contact(new Contact("岳立民", "https://github.com/YueLiMin-say", "yueliminvc@outlook.com"))
                .build();
    }

}

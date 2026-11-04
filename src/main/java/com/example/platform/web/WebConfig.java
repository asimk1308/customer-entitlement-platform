package com.example.platform.web;

import com.example.platform.Settings;
import com.example.platform.security.Principal;
import com.example.platform.security.ScopeInterceptor;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final ScopeInterceptor scopes;

    public WebConfig(ScopeInterceptor scopes) {
        this.scopes = scopes;
    }

    @Bean
    Cursors cursors(JsonMapper json, Settings settings) {
        return new Cursors(json, settings.cursorSecret());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(scopes);
    }

    /** The contract repeats list parameters (`explode: true`), so a comma is not a separator. */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.removeConvertible(String.class, Collection.class);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == Principal.class;
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                          NativeWebRequest request, WebDataBinderFactory binders) {
                return request.getAttribute(Principal.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            }
        });
    }
}

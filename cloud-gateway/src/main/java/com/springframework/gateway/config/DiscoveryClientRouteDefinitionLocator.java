package com.springframework.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.style.ToStringCreator;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.Map;

/**
 * @author summer 基于eureka 服务发现 动态路由
 * 2018/7/4
 */
@Slf4j
public class DiscoveryClientRouteDefinitionLocator implements RouteDefinitionLocator {

    private final DiscoveryClient discoveryClient;
    private final DiscoveryLocatorProperties properties;
    private final String routeIdPrefix;

    public DiscoveryClientRouteDefinitionLocator(DiscoveryClient discoveryClient, DiscoveryLocatorProperties properties) {
        this.discoveryClient = discoveryClient;
        this.properties = properties;
        if (StringUtils.hasText(properties.getRouteIdPrefix())) {
            this.routeIdPrefix = properties.getRouteIdPrefix();
        } else {
            this.routeIdPrefix = this.discoveryClient.getClass().getSimpleName() + "_";
        }
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        SimpleEvaluationContext evalCtxt = SimpleEvaluationContext
                .forReadOnlyDataBinding()
                .withInstanceMethods()
                .build();

        SpelExpressionParser parser = new SpelExpressionParser();
        Expression includeExpr = parser.parseExpression(properties.getIncludeExpression());
        Expression urlExpr = parser.parseExpression(properties.getUrlExpression());

        return Flux.fromIterable(discoveryClient.getServices())
                .map(discoveryClient::getInstances)
                .filter(instances -> !instances.isEmpty())
                .map(instances -> instances.get(0))
                .filter(instance -> {
                    Boolean include = includeExpr.getValue(evalCtxt, instance, Boolean.class);
                    if (include == null) {
                        return false;
                    }
                    return include;
                })
                .map(instance -> {
                    String serviceId = instance.getServiceId();

                    RouteDefinition routeDefinition = new RouteDefinition();
                    routeDefinition.setId(this.routeIdPrefix + serviceId);
                    String uri = urlExpr.getValue(evalCtxt, instance, String.class);
                    routeDefinition.setUri(URI.create(uri));

                    final ServiceInstance instanceForEval = new DiscoveryClientRouteDefinitionLocator.DelegatingServiceInstance(instance, properties);

                    for (PredicateDefinition original : this.properties.getPredicates()) {
                        PredicateDefinition predicate = new PredicateDefinition();
                        predicate.setName(original.getName());
                        for (Map.Entry<String, String> entry : original.getArgs().entrySet()) {
                            String value = getValueFromExpr(evalCtxt, parser, instanceForEval, entry);
                            predicate.addArg(entry.getKey(), value);
                        }
                        routeDefinition.getPredicates().add(predicate);
                    }

                    for (FilterDefinition original : this.properties.getFilters()) {
                        FilterDefinition filter = new FilterDefinition();
                        filter.setName(original.getName());
                        for (Map.Entry<String, String> entry : original.getArgs().entrySet()) {
                            String value = getValueFromExpr(evalCtxt, parser, instanceForEval, entry);
                            filter.addArg(entry.getKey(), value);
                        }
                        routeDefinition.getFilters().add(filter);
                    }

                    return routeDefinition;
                });
    }

    String getValueFromExpr(SimpleEvaluationContext evalCtxt, SpelExpressionParser parser, ServiceInstance instance, Map.Entry<String, String> entry) {
        Expression valueExpr = parser.parseExpression(entry.getValue());
        return valueExpr.getValue(evalCtxt, instance, String.class);
    }

    private static class DelegatingServiceInstance implements ServiceInstance {

        final ServiceInstance delegate;
        private final DiscoveryLocatorProperties properties;

        private DelegatingServiceInstance(ServiceInstance delegate, DiscoveryLocatorProperties properties) {
            this.delegate = delegate;
            this.properties = properties;
        }

        @Override
        public String getServiceId() {
            if (properties.isLowerCaseServiceId()) {
                return delegate.getServiceId().toLowerCase();
            }
            return delegate.getServiceId();
        }

        @Override
        public String getHost() {
            return delegate.getHost();
        }

        @Override
        public int getPort() {
            return delegate.getPort();
        }

        @Override
        public boolean isSecure() {
            return delegate.isSecure();
        }

        @Override
        public URI getUri() {
            return delegate.getUri();
        }

        @Override
        public Map<String, String> getMetadata() {
            return delegate.getMetadata();
        }

        @Override
        public String getScheme() {
            return delegate.getScheme();
        }

        @Override
        public String toString() {
            return new ToStringCreator(this)
                    .append("delegate", delegate)
                    .append("properties", properties)
                    .toString();
        }
    }
}
